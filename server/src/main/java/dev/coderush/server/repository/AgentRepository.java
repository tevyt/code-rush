package dev.coderush.server.repository;

import dev.coderush.common.model.AgentState;
import dev.coderush.server.model.Agent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AgentRepository extends JpaRepository<Agent, Long> {

    Optional<Agent> findByName(String name);

    List<Agent> findByState(AgentState state);

    List<Agent> findByStateAndLastHeartbeatBefore(AgentState state, Instant cutoff);
}
