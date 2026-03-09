package com.firstclub.payments.repository;

import com.firstclub.payments.entity.Refund;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RefundRepository extends JpaRepository<Refund, Long> {

    List<Refund> findByPaymentId(Long paymentId);
}
