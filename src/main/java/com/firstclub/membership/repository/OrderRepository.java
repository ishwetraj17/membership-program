package com.firstclub.membership.repository;

import com.firstclub.membership.entity.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;

public interface OrderRepository extends JpaRepository<Order, Long> {

    /** Lifetime savings for a user — member + coupon discounts across their placed orders. */
    @Query("SELECT COALESCE(SUM(o.memberDiscount + o.couponDiscount), 0) FROM Order o WHERE o.userId = :userId")
    BigDecimal totalSavings(@Param("userId") Long userId);
}
