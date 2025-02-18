package searchengine.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.jpa.repository.Modifying;
import searchengine.model.Page;
import searchengine.model.Site;
import org.springframework.data.repository.query.Param;

@Repository
public interface PageRepository extends JpaRepository<Page, Integer> {

    @Modifying
    @Transactional
    @Query("DELETE FROM Page p WHERE p.site.id = :siteId")
    int deleteAllBySiteId(int siteId);

    @Query("SELECT COUNT(p) > 0 FROM Page p WHERE p.path = :path AND p.site.id = :siteId")
    boolean existsByPathAndSiteId(String path, int siteId);

    Optional<Page> findBySiteAndPath(Site site, String path);




    @Modifying
    @Transactional
    @Query("DELETE FROM Page p WHERE p.site = :site")
    void deleteAllBySite(@Param("site") Site site);

    long countBySite(Site site);

}
