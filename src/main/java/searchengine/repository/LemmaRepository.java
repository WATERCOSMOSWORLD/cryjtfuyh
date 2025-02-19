package searchengine.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import searchengine.model.Lemma;

import java.util.Optional;

@Repository
public interface LemmaRepository extends JpaRepository<Lemma, Long> {

    Optional<Lemma> findByLemma(String lemma);

}

