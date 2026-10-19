package com.actify.invoice.repository;

import com.actify.invoice.entity.ReviewerCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ReviewerCategoryRepository extends JpaRepository<ReviewerCategory, Long> {

    @Query("select rc.categoryCode from ReviewerCategory rc where rc.userId = :userId order by rc.categoryCode")
    List<String> findCategoryCodesOfReviewer(@Param("userId") Long userId);

    @Query("select count(rc) > 0 from ReviewerCategory rc where rc.userId = :userId and rc.categoryCode = :code")
    boolean reviewerHasCategory(@Param("userId") Long userId, @Param("code") String categoryCode);

    /** Replacing a reviewer's category list is the one place rows are removed (it is configuration, not history). */
    @Modifying
    @Query("delete from ReviewerCategory rc where rc.userId = :userId")
    void deleteAllOfReviewer(@Param("userId") Long userId);
}
