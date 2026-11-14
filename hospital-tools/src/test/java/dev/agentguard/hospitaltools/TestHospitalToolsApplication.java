package dev.agentguard.hospitaltools;

import org.springframework.boot.SpringApplication;

public class TestHospitalToolsApplication {

	public static void main(String[] args) {
		SpringApplication.from(HospitalToolsApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
