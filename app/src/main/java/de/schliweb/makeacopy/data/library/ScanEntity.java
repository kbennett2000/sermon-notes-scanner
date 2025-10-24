package de.schliweb.makeacopy.data.library;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * Represents a scanned entity stored in the application's local database.
 * This entity holds metadata and details about a completed scan, such as
 * its identifier, title, creation timestamp, page count, cover image path,
 * export paths, and source metadata.
 * <p>
 * Fields:
 * - id: A unique identifier (UUID) for the scan.
 * - title: An optional human-readable title for the scan.
 * - createdAt: The timestamp when the scan was created, stored as epoch milliseconds.
 * - pageCount: The number of pages detected or exported in the scan.
 * - coverPath: An optional path or URI pointing to the scan's thumbnail or cover image.
 * - exportPathsJson: An optional JSON string containing the export file paths of the scan.
 * - sourceMetaJson: An optional JSON string describing the source metadata of the scan.
 * <p>
 * This entity is annotated with @Entity to represent a table in the Room database.
 * The table name is defined as "scans".
 */
@Entity(tableName = "scans")
public class ScanEntity {
    @PrimaryKey
    @NonNull
    public String id; // UUID as string

    public String title; // optional human title

    public long createdAt; // epoch millis

    public int pageCount; // number of pages detected/exported

    public String coverPath; // optional path/uri to a thumbnail/cover

    public String exportPathsJson; // optional JSON of recent export file paths

    public String sourceMetaJson; // optional source metadata JSON

    public ScanEntity(@NonNull String id, String title, long createdAt, int pageCount,
                      String coverPath, String exportPathsJson, String sourceMetaJson) {
        this.id = id;
        this.title = title;
        this.createdAt = createdAt;
        this.pageCount = pageCount;
        this.coverPath = coverPath;
        this.exportPathsJson = exportPathsJson;
        this.sourceMetaJson = sourceMetaJson;
    }
}