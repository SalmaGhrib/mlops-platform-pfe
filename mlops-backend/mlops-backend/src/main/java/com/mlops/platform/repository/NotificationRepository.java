package com.mlops.platform.repository;

import com.mlops.platform.entity.Notification;
import com.mlops.platform.entity.NotificationTargetType;
import com.mlops.platform.entity.NotificationType;
import com.mlops.platform.entity.User;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface NotificationRepository
        extends JpaRepository<Notification, Long> {

    List<Notification>
    findTop20ByUserAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(
            User user,
            LocalDateTime createdAt
    );

    List<Notification> findByUserOrderByCreatedAtDesc(
            User user
    );

    long countByUserAndReadFalseAndCreatedAtGreaterThanEqual(
            User user,
            LocalDateTime createdAt
    );

    List<Notification> findByUserAndReadFalseOrderByCreatedAtDesc(
            User user
    );

    boolean existsByUserAndTypeAndTargetTypeAndTargetIdAndTitle(
            User user,
            NotificationType type,
            NotificationTargetType targetType,
            Long targetId,
            String title
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        value = """
            delete from notifications
             where created_at < :cutoff
             order by id
             limit :batchSize
        """,
        nativeQuery = true
    )
    int deleteOldNotificationsBatch(
            @Param("cutoff") LocalDateTime cutoff,
            @Param("batchSize") int batchSize
    );

    /**
     * Marque toutes les notifications non lues d'un utilisateur
     * avec une seule requête SQL.
     */
    @Modifying(
            clearAutomatically = true,
            flushAutomatically = true
    )
    @Query("""
        update Notification notification
           set notification.read = true
         where notification.user.id = :userId
           and notification.read = false
           and notification.createdAt >= :cutoff
    """)
    int markRecentAsReadByUserId(
            @Param("userId") Long userId,
            @Param("cutoff") LocalDateTime cutoff
    );

    /**
     * Marque une notification comme lue uniquement si elle
     * appartient à l'utilisateur connecté.
     */
    @Modifying(
            clearAutomatically = true,
            flushAutomatically = true
    )
    @Query("""
        update Notification notification
           set notification.read = true
         where notification.id = :notificationId
           and notification.user.id = :userId
           and notification.read = false
    """)
    int markAsReadByNotificationIdAndUserId(
            @Param("notificationId") Long notificationId,
            @Param("userId") Long userId
    );
}