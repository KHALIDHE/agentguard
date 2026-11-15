package dev.agentguard.gateway.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "app_user")
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 60, updatable = false)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @Column(length = 16)
    private String department;

    /** Links a manager to their row in the finance's EMPLOYEE table, for "my suppliers" scoping. */
    @Column(name = "finance_employee_id")
    private Long financeEmployeeId;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AppUser() {
        // for JPA
    }

    public AppUser(String username, String passwordHash, Role role, String department, Long financeEmployeeId) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.role = role;
        this.department = department;
        this.financeEmployeeId = financeEmployeeId;
        this.active = true;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getUsername() { return username; }
    public String getPasswordHash() { return passwordHash; }
    public Role getRole() { return role; }
    public String getDepartment() { return department; }
    public Long getFinanceEmployeeId() { return financeEmployeeId; }
    public boolean isActive() { return active; }
}
