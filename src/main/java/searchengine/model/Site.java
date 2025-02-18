package searchengine.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(
        name = "site",
        indexes = {@jakarta.persistence.Index(name = "idx_url", columnList = "url")}
)
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Site {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private IndexingStatus status;

    @Column(name = "status_time", nullable = false)
    private LocalDateTime statusTime;

    @Column(columnDefinition = "TEXT")
    private String lastError;

    @Column(length = 500, nullable = false, unique = true)
    private String url;

    @Column(length = 500, nullable = false)
    private String name;

    @OneToMany(mappedBy = "site", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Page> pages = new ArrayList<>();

    public void addPage(Page page) {
        pages.add(page);
        page.setSite(this);
    }

    public void removePage(Page page) {
        pages.remove(page);
        page.setSite(null);
    }

    public void clearPages() {
        for (Page page : new ArrayList<>(pages)) {
            removePage(page);
        }
    }

    // Helper method for updating the status, status time, and error message
    public void updateStatus(IndexingStatus newStatus, String errorMessage) {
        this.status = newStatus;
        this.statusTime = LocalDateTime.now();
        this.lastError = errorMessage;
    }

    // Helper method to check if the site is currently being indexed
    public boolean isIndexing() {
        return this.status == IndexingStatus.INDEXING;
    }
}
