package wj.flab.group_wise.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import wj.flab.group_wise.domain.groupPurchase.GroupPurchase;
import wj.flab.group_wise.domain.groupPurchase.GroupPurchase.Status;
import wj.flab.group_wise.dto.groupPurchase.request.GroupPurchaseStats;

@Repository
public interface GroupPurchaseRepository extends JpaRepository<GroupPurchase, Long>, GroupPurchaseRepositoryCustom {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select gp from GroupPurchase gp where gp.id = :id")
    Optional<GroupPurchase> findByIdWithUpdate(Long id);

    @Query("SELECT gp FROM GroupPurchase gp "
        + "JOIN Product p ON gp.productId = p.id "
        + "WHERE gp.status = :status AND p.id = :productId")
    List<GroupPurchase> findGroupPurchaseByProductAndStatus(
        @Param("status") GroupPurchase.Status status,
        @Param("productId") Long productId);

    List<GroupPurchase> findByStatusAndEndDateBefore(Status status, LocalDateTime now);

    @Query("SELECT new wj.flab.group_wise.dto.groupPurchase.request.GroupPurchaseStats("
        + "COUNT(CASE WHEN gpm.hasParticipated = true THEN 1 END), "
        + "COUNT(CASE WHEN gpm.isWishlist = true THEN 1 END)) " +
        "FROM GroupPurchaseMember gpm " +
        "WHERE gpm.groupPurchase.id = :groupId")
    GroupPurchaseStats getGroupPurchaseStats(Long groupId);
}

