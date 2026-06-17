package com.firstclub.membership.repository;

import com.firstclub.membership.entity.AppAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AppAccountRepository extends JpaRepository<AppAccount, Long> {

    Optional<AppAccount> findByUsername(String username);

    boolean existsByUsername(String username);
}
