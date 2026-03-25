/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.data.library;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

/**
 * AppDatabase is the main database class for the application. It serves as a primary access point
 * to the defined Room Database. This database includes several entities and their relationships,
 * allowing data persistence for scans, collections, and their associations.
 *
 * <p>The database supports the following entities: - ScanEntity: Represents a completed scan in the
 * local index. - CollectionEntity: Represents a collection, which can group multiple scans. -
 * ScanCollectionCrossRef: Represents the many-to-many relationship between scans and collections.
 *
 * <p>It defines three DAOs (Data Access Objects) for interacting with the database: - ScansDao:
 * Provides CRUD operations and queries for ScanEntity. - CollectionsDao: Provides CRUD operations
 * and queries for CollectionEntity. - ScanCollectionJoinDao: Provides operations for managing
 * relationships between scans and collections.
 *
 * <p>The database is implemented as a singleton to ensure a single instance is used across the
 * application. It also allows fallback to destructive migration, suitable for prototyping or
 * initial development.
 */
@Database(
    entities = {ScanEntity.class, CollectionEntity.class, ScanCollectionCrossRef.class},
    version = 1,
    exportSchema = false)
public abstract class AppDatabase extends RoomDatabase {

  private static volatile AppDatabase INSTANCE;

  public abstract ScansDao scansDao();

  public abstract CollectionsDao collectionsDao();

  public abstract ScanCollectionJoinDao scanCollectionJoinDao();

  /**
   * Retrieves the singleton instance of the AppDatabase. If the instance is not yet created, it
   * initializes a new AppDatabase using the Room database builder. This method ensures thread-safe
   * lazy initialization of the database instance.
   *
   * @param context the context of the application or activity, used to access the application
   *     context and initialize the Room database.
   * @return the singleton instance of AppDatabase.
   */
  public static AppDatabase getInstance(@NonNull Context context) {
    if (INSTANCE == null) {
      synchronized (AppDatabase.class) {
        if (INSTANCE == null) {
          INSTANCE =
              Room.databaseBuilder(
                      context.getApplicationContext(), AppDatabase.class, "scan_library.db")
                  .fallbackToDestructiveMigration(false) // safe for MVP; will be replaced with
                  // proper migrations later
                  .build();
        }
      }
    }
    return INSTANCE;
  }
}
