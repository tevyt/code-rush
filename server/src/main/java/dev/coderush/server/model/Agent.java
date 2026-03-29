package dev.coderush.server.model;

import dev.coderush.common.model.AgentState;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "agents")
public class Agent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String address;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AgentState state;

    @Column(name = "last_heartbeat")
    private Instant lastHeartbeat;

    @Column(name = "registered_at", nullable = false, updatable = false)
    private Instant registeredAt;

    @PrePersist
    void prePersist() {
        registeredAt = Instant.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }

    public AgentState getState() { return state; }
    public void setState(AgentState state) { this.state = state; }

    public Instant getLastHeartbeat() { return lastHeartbeat; }
    public void setLastHeartbeat(Instant lastHeartbeat) { this.lastHeartbeat = lastHeartbeat; }

    public Instant getRegisteredAt() { return registeredAt; }
}
