package com.firstclub.risk.repository;

import com.firstclub.risk.entity.IpBlocklist;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IpBlocklistRepository extends JpaRepository<IpBlocklist, String> {
}
