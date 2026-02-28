package de.schliweb.makeacopy.di;

import android.content.Context;
import dagger.Module;
import dagger.Provides;
import dagger.hilt.InstallIn;
import dagger.hilt.android.qualifiers.ApplicationContext;
import dagger.hilt.components.SingletonComponent;
import de.schliweb.makeacopy.data.library.AppDatabase;
import de.schliweb.makeacopy.data.library.CollectionsDao;
import de.schliweb.makeacopy.data.library.CollectionsRepository;
import de.schliweb.makeacopy.data.library.DefaultCollectionsRepository;
import de.schliweb.makeacopy.data.library.DefaultScansRepository;
import de.schliweb.makeacopy.data.library.ScanCollectionJoinDao;
import de.schliweb.makeacopy.data.library.ScansDao;
import de.schliweb.makeacopy.data.library.ScansRepository;
import de.schliweb.makeacopy.utils.DictionaryManager;
import javax.inject.Singleton;

/** Hilt module that provides database-related dependencies as singletons. */
@Module
@InstallIn(SingletonComponent.class)
public class DatabaseModule {

  @Provides
  @Singleton
  AppDatabase provideAppDatabase(@ApplicationContext Context context) {
    return AppDatabase.getInstance(context);
  }

  @Provides
  ScansDao provideScansDao(AppDatabase db) {
    return db.scansDao();
  }

  @Provides
  CollectionsDao provideCollectionsDao(AppDatabase db) {
    return db.collectionsDao();
  }

  @Provides
  ScanCollectionJoinDao provideScanCollectionJoinDao(AppDatabase db) {
    return db.scanCollectionJoinDao();
  }

  @Provides
  @Singleton
  ScansRepository provideScansRepository() {
    return new DefaultScansRepository();
  }

  @Provides
  @Singleton
  CollectionsRepository provideCollectionsRepository() {
    return new DefaultCollectionsRepository();
  }

  @Provides
  @Singleton
  DictionaryManager provideDictionaryManager(@ApplicationContext Context context) {
    return DictionaryManager.getInstance(context);
  }
}
