/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.ui.camera;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import de.schliweb.makeacopy.ui.BaseViewModel;

/**
 * ViewModel for managing camera-related operations within a specific fragment or activity. Inherits
 * from BaseViewModel to provide lifecycle-aware operations and LiveData management. This ViewModel
 * handles the state of camera permissions and image path updates.
 */
public class CameraViewModel extends BaseViewModel {

  private final MutableLiveData<Boolean> mCameraPermissionGranted;
  private final MutableLiveData<String> mImagePath = new MutableLiveData<>();

  public CameraViewModel() {
    super("Camera Fragment");

    mCameraPermissionGranted = new MutableLiveData<>();
    mCameraPermissionGranted.setValue(false);
  }

  /**
   * Checks whether the camera permission is granted.
   *
   * @return A LiveData object containing a Boolean value indicating if the camera permission is
   *     granted. Returns true if the permission is granted, false otherwise.
   */
  public LiveData<Boolean> isCameraPermissionGranted() {
    return mCameraPermissionGranted;
  }

  /**
   * Updates the camera permission status.
   *
   * @param granted A boolean value indicating whether the camera permission has been granted. Pass
   *     true if the permission is granted, false otherwise.
   */
  public void setCameraPermissionGranted(boolean granted) {
    mCameraPermissionGranted.setValue(granted);
  }

  /**
   * Retrieves the LiveData object representing the image path. The image path is expected to be
   * updated when a new image is captured or selected.
   *
   * @return A MutableLiveData object containing the current image path as a String.
   */
  public MutableLiveData<String> getImagePath() {
    return mImagePath;
  }

  /**
   * Updates the image path stored in the ViewModel.
   *
   * @param path The new image path as a String. This value is used to update the current image
   *     path.
   */
  public void setImagePath(String path) {
    mImagePath.setValue(path);
  }
}
