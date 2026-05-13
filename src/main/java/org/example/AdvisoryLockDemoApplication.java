package org.example;

import org.example.lock.IAdvisoryLockService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.example.lock.LockResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

@SpringBootApplication
public class AdvisoryLockDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(AdvisoryLockDemoApplication.class, args);
    }

    @Bean
    CommandLineRunner runner(IAdvisoryLockService lockService,
                             PlatformTransactionManager txManager,
                             org.springframework.jdbc.core.JdbcTemplate jdbcTemplate) {
        return args -> {
            // We use TransactionTemplate to wrap the lock acquisition and the work 
            // in a single transaction. Since pg_advisory_xact_lock is transaction-scoped,
            // it will be released automatically when this transaction commits.
            TransactionTemplate tx = new TransactionTemplate(txManager);
            String instanceId = System.getProperty("instance.id", "default");

            while (true) {
                try {
                    tx.execute(status -> {
                        try {
                            long start = System.currentTimeMillis();
                            writeLog(instanceId + " TRYING at " + start);

                            // Acquire lock using our service
                            lockService.executeWithLock(LockResource.SYSTEM_SYNC, java.time.Duration.ofSeconds(10), () -> {
                                try {
                                    // Fetch the PostgreSQL backend PID for this session
                                    Integer pid = jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class);

                                    long wait = System.currentTimeMillis() - start;
                                    writeLog(instanceId + " ACQUIRED (DB PID: " + pid + ") after " + wait + " ms. Holding for 60 seconds...");

                                    // Simulate work and pause execution for 60 seconds to allow DB inspection
                                    Thread.sleep(15000);

                                    writeLog(instanceId + " RELEASING LOCK (DB PID: " + pid + ")");
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                            });

                        } catch (Exception e) {
                            writeLog(instanceId + " ERROR: " + e.getMessage());
                            throw new RuntimeException(e);
                        }
                        return null;
                    });

                    Thread.sleep(2000);

                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        };
    }

    private static synchronized void writeLog(String message) {
        try {
            String line = System.currentTimeMillis() + " | " + message + "\n";
            Files.writeString(
                    Path.of("lock-demo.log"),
                    line,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
            System.out.print(line); // Also print to console for visibility
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}