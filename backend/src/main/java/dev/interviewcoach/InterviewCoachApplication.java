package dev.interviewcoach;

import dev.interviewcoach.identity.TrialAccountProvisioningPreflight;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class InterviewCoachApplication {

    public static void main(String[] args) {
        var application = new SpringApplication(InterviewCoachApplication.class);
        application.addListeners(new TrialAccountProvisioningPreflight());
        application.run(args);
    }
}
