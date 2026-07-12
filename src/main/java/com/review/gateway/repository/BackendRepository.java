package com.review.gateway.repository;

import com.review.gateway.model.Backend;
import com.review.gateway.model.enums.BackendStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for the {@link Backend} (llama-server) registry.
 */
public interface BackendRepository extends JpaRepository<Backend, Long> {

    Optional<Backend> findByName(String name);

    List<Backend> findByStatus(BackendStatus status);
}
