package com.cardamage.repository;

import com.cardamage.model.AssessmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AssessmentRepository extends JpaRepository<AssessmentEntity, String> {
}
