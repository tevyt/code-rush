package dev.coderush.server.repository;

import dev.coderush.common.model.BuildStatus;
import dev.coderush.server.model.Build;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BuildRepository extends JpaRepository<Build, Long> {

    List<Build> findByStatus(BuildStatus status);

    List<Build> findByBuildConfigId(Long buildConfigId);
}
