package com.mlops.platform.repository;

import com.mlops.platform.entity.Notification;
import com.mlops.platform.entity.NotificationTargetType;
import com.mlops.platform.entity.NotificationType;
import com.mlops.platform.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;

import java.util.List;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    List<Notification> findTop20ByUserAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(User user, LocalDateTime createdAt);

    List<Notification> findByUserOrderByCreatedAtDesc(User user);

    long countByUserAndReadFalseAndCreatedAtGreaterThanEqual(User user, LocalDateTime createdAt);

    List<Notification> findByUserAndReadFalseOrderByCreatedAtDesc(User user);

    boolean existsByUserAndTypeAndTargetTypeAndTargetId(
            User user,
            NotificationType type,
            NotificationTargetType targetType,
            Long targetId
    );

    void deleteByCreatedAtBefore(LocalDateTime dateTime);
}