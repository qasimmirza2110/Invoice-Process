package com.actify.invoice.repository;

import com.actify.invoice.entity.UploadBatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import javax.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

/**
 * Upload batches. A vendor only ever sees their own batches (BatchIntakeService filters by owner); the row lock
 * serialises concurrent uploads into the same batch so its file limit and counters stay exact.
 */
public interface UploadBatchRepository extends JpaRepository<UploadBatch, Long> {

    /**
     * SELECT ... FOR UPDATE: two uploads into the same batch run one after the other, so the
     * "at most 100 files" count can never be exceeded by two requests at the same moment.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from UploadBatch b where b.id = :id")
    Optional<UploadBatch> findByIdForUpdate(@Param("id") Long id);

    List<UploadBatch> findAllByOwnerUserIdOrderByIdDesc(Long ownerUserId);

    List<UploadBatch> findAllByOrderByIdDesc();
}
