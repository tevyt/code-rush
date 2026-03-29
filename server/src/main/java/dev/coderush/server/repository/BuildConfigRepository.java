package dev.coderush.server.repository;

import dev.coderush.server.model.BuildConfig;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BuildConfigRepository extends JpaRepository<BuildConfig, Long> {
}
