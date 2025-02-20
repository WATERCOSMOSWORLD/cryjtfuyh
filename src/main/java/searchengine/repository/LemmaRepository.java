package searchengine.repository;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;
import searchengine.model.Lemma;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface LemmaRepository extends JpaRepository<Lemma, Long> {

    Optional<Lemma> findByLemma(String lemma);

    @Modifying
    @Transactional
    @Query("DELETE FROM Lemma l WHERE l.site.id = :siteId")
    int deleteBySiteId(Long siteId);

    @Modifying
    @Transactional
    @Query("DELETE FROM Lemma l WHERE l.id NOT IN (SELECT DISTINCT i.lemma.id FROM Index i)")
    int deleteOrphanLemmas();
}
