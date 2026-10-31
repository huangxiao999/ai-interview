package dev.interviewcoach;

import dev.interviewcoach.identity.AccountMaintenancePreflight;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class InterviewCoachApplication {

    public static void main(String[] args) {
        if (args.length == 5 && args[0].equals("--parser-worker")) {
            try {
                dev.interviewcoach.material.ParserWorker.main(java.util.Arrays.copyOfRange(args, 1, 5));
            } catch (Exception ex) { System.exit(1); }
            return;
        }
        var application = new SpringApplication(InterviewCoachApplication.class);
        application.addListeners(new AccountMaintenancePreflight());
        application.run(args);
    }
}
