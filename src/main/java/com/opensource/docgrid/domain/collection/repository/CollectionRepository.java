package com.opensource.docgrid.domain.collection.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.opensource.docgrid.domain.collection.entity.DocumentCollection;

public interface CollectionRepository extends JpaRepository<DocumentCollection, Long> {
}
