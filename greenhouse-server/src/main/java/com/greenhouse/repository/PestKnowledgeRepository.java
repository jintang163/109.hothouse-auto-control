package com.greenhouse.repository;

import com.greenhouse.entity.PestKnowledge;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PestKnowledgeRepository extends JpaRepository<PestKnowledge, Long> {

    List<PestKnowledge> findAllByOrderByIdAsc();

    List<PestKnowledge> findByCategory(String category);

    List<PestKnowledge> findByNameContainingOrCropsContaining(String name, String crops);
}
