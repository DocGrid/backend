package com.opensource.docgrid.domain.user.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.opensource.docgrid.domain.user.entity.Role;

public interface RoleRepository extends JpaRepository<Role, Long> {

    Optional<Role> findByCode(String code);
}
