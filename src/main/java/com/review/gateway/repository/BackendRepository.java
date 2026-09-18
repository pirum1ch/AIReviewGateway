package com.review.gateway.repository;

import com.review.gateway.model.Backend;
import com.review.gateway.model.enums.BackendStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Repository for the {@link Backend} (llama-server) registry.
 */
public interface BackendRepository extends JpaRepository<Backend, Long> {

    Optional<Backend> findByName(String name);

    List<Backend> findByStatus(BackendStatus status);

    /**
     * Backend Self-Registration (BSQ-08): the pessimistic-write read {@code BackendRegistryService} takes
     * before an announce/admin-upsert mutates an existing row, so two concurrent announces of the same
     * name serialize and the {@code announced_by} ownership check (§2.4) cannot race.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Backend b where b.name = :name")
    Optional<Backend> findByNameForUpdate(@Param("name") String name);
}
