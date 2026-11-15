package dev.agentguard.financetools;

import org.springframework.boot.SpringApplication;

public class TestFinanceToolsApplication {

	public static void main(String[] args) {
		SpringApplication.from(FinanceToolsApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
