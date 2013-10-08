/*
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.camera;

import com.android.gallery.R;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Parcelable;
import android.preference.PreferenceManager;
import android.provider.MediaStore;
import android.util.Log;
import android.view.ContextMenu;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.view.View.OnClickListener;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.android.camera.gallery.IImage;
import com.android.camera.gallery.IImageList;
import com.android.camera.gallery.VideoObject;

import java.util.ArrayList;
import java.util.HashSet;

public class ImageGallery extends NoSearchActivity implements
        GridViewSpecial.Listener, GridViewSpecial.DrawAdapter {
    private static final String STATE_SCROLL_POSITION = "scroll_position";
    private static final String STATE_SELECTED_INDEX = "first_index";

    private static final String TAG = "ImageGallery";
    private static final float INVALID_POSITION = -1f;
    private ImageManager.ImageListParam mParam;
    private IImageList mAllImages;
    private int mInclusion;
    boolean mSortAscending = false;
    private View mNoImagesView;
    public static final int CROP_MSG = 2;

    private Dialog mMediaScanningDialog;
    private MenuItem mSlideShowItem;
    private SharedPreferences mPrefs;
    private long mVideoSizeLimit = Long.MAX_VALUE;
    private View mFooterOrganizeView;

    private BroadcastReceiver mReceiver = null;

    private final Handler mHandler = new Handler();
    private boolean mLayoutComplete;
    private boolean mPausing = true;
    private ImageLoader mLoader;
    private GridViewSpecial mGvs;

    private Uri mCropResultUri;

    // The index of the first picture in GridViewSpecial.
    private int mSelectedIndex = GridViewSpecial.INDEX_NONE;
    private float mScrollPosition = INVALID_POSITION;
    private boolean mConfigurationChanged = false;

    private HashSet<IImage> mMultiSelected = null;

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        mPrefs = PreferenceManager.getDefaultSharedPreferences(this);

        // Must be called before setContentView().
        requestWindowFeature(Window.FEATURE_CUSTOM_TITLE);

        setContentView(R.layout.image_gallery);

        getWindow().setFeatureInt(Window.FEATURE_CUSTOM_TITLE,
                R.layout.custom_gallery_title);

        mNoImagesView = findViewById(R.id.no_images);

        mGvs = (GridViewSpecial) findViewById(R.id.grid);
        mGvs.setListener(this);

        mFooterOrganizeView = findViewById(R.id.footer_organize);

        // consume all click events on the footer view
        mFooterOrganizeView.setOnClickListener(Util.getNullOnClickListener());
        initializeFooterButtons();

        if (isPickIntent()) {
            mVideoSizeLimit = getIntent().getLongExtra(
                    MediaStore.EXTRA_SIZE_LIMIT, Long.MAX_VALUE);
        } else {
            mVideoSizeLimit = Long.MAX_VALUE;
            mGvs.setOnCreateContextMenuListener(
                    new CreateContextMenuListener());
        }

        setupInclusion();
        mInclusion &= ~ImageManager.INCLUDE_VIDEOS;

        mLoader = new ImageLoader(getContentResolver(), mHandler);
    }

    private void initializeFooterButtons() {
        Button deleteButton = (Button) findViewById(R.id.button_delete);
        deleteButton.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                onDeleteMultipleClicked();
            }
        });

        Button closeButton = (Button) findViewById(R.id.button_close);
        closeButton.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                closeMultiSelectMode();
            }
        });
    }

    private MenuItem addSlideShowMenu(Menu menu) {
        return menu.add(Menu.NONE, Menu.NONE, MenuHelper.POSITION_SLIDESHOW,
                R.string.slide_show)
                .setOnMenuItemClickListener(
                new MenuItem.OnMenuItemClickListener() {
                    public boolean onMenuItemClick(MenuItem item) {
                        return onSlideShowClicked();
                    }
                }).setIcon(android.R.drawable.ic_menu_slideshow);
    }

    public boolean onSlideShowClicked() {
        if (!canHandleEvent()) {
            return false;
        }
        IImage img = getCurrentImage();
        if (img == null) {
            img = mAllImages.getImageAt(0);
            if (img == null) {
                return true;
            }
        }
        Uri targetUri = img.fullSizeImageUri();
        Uri thisUri = getIntent().getData();
        if (thisUri != null) {
            String bucket = thisUri.getQueryParameter("bucketId");
            if (bucket != null) {
                targetUri = targetUri.buildUpon()
                        .appendQueryParameter("bucketId", bucket)
                        .build();
            }
        }
        Intent intent = new Intent(Intent.ACTION_VIEW, targetUri);
        intent.putExtra("slideshow", true);
        startActivity(intent);
        return true;
    }

    private final Runnable mDeletePhotoRunnable = new Runnable() {
        public void run() {
            if (!canHandleEvent()) return;

            IImage currentImage = getCurrentImage();

            // The selection will be cleared when mGvs.stop() is called, so
            // we need to call getCurrentImage() before mGvs.stop().
            mGvs.stop();

            if (currentImage != null) {
                mAllImages.removeImage(currentImage);
            }
            mGvs.setImageList(mAllImages);
            mGvs.start();

            mNoImagesView.setVisibility(mAllImages.isEmpty()
                    ? View.VISIBLE
                    : View.GONE);
        }
    };

    private Uri getCurrentImageUri() {
        IImage image = getCurrentImage();
        if (image != null) {
            return image.fullSizeImageUri();
        } else {
            return null;
        }
    }

    private IImage getCurrentImage() {
        int currentSelection = mGvs.getCurrentSelection();
        if (currentSelection < 0
                || currentSelection >= mAllImages.getCount()) {
            return null;
        } else {
            return mAllImages.getImageAt(currentSelection);
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mConfigurationChanged = true;
    }

    boolean canHandleEvent() {
        // Don't process event in pause state.
        return (!mPausing) && (mLayoutComplete);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (!canHandleEvent()) return false;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DEL:
                IImage image = getCurrentImage();
                if (image != null) {
                    MenuHelper.deleteImage(
                            this, mDeletePhotoRunnable, getCurrentImage());
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private boolean isPickIntent() {
        String action = getIntent().getAction();
        return (Intent.ACTION_PICK.equals(action)
                || Intent.ACTION_GET_CONTENT.equals(action));
    }

    private void launchCropperOrFinish(IImage img) {
        Bundle myExtras = getIntent().getExtras();

        long size = MenuHelper.getImageFileSize(img);
        if (size < 0) {
            // Return if the image file is not available.
            return;
        }

        if (size > mVideoSizeLimit) {
            DialogInterface.OnClickListener buttonListener =
                    new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int which) {
                    dialog.dismiss();
                }
            };
            new AlertDialog.Builder(this)
                    .setIcon(android.R.drawable.ic_dialog_info)
                    .setTitle(R.string.file_info_title)
                    .setMessage(R.string.video_exceed_mms_limit)
                    .setNeutralButton(R.string.details_ok, buttonListener)
                    .show();
            return;
        }

        String cropValue = myExtras != null ? myExtras.getString("crop") : null;
        if (cropValue != null) {
            Bundle newExtras = new Bundle();
            if (cropValue.equals("circle")) {
                newExtras.putString("circleCrop", "true");
            }

            Intent cropIntent = new Intent();
            cropIntent.setData(img.fullSizeImageUri());
            cropIntent.setClass(this, CropImage.class);
            cropIntent.putExtras(newExtras);

            /* pass through any extras that were passed in */
            cropIntent.putExtras(myExtras);
            startActivityForResult(cropIntent, CROP_MSG);
        } else {
            Intent result = new Intent(null, img.fullSizeImageUri());
            if (myExtras != null && myExtras.getBoolean("return-data")) {
                // The size of a transaction should be below 100K.
                Bitmap bitmap = img.fullSizeBitmap(
                        IImage.UNCONSTRAINED, 100 * 1024);
                if (bitmap != null) {
                    result.putExtra("data", bitmap);
                }
            }
            setResult(RESULT_OK, result);
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode,
            Intent data) {
        switch (requestCode) {
            case MenuHelper.RESULT_COMMON_MENU_CROP: {
                if (resultCode == RESULT_OK) {

                    // The CropImage activity passes back the Uri of the cropped
                    // image as the Action rather than the Data.
                    // We store this URI so we can move the selection box to it
                    // later.
                    mCropResultUri = Uri.parse(data.getAction());
                }
                break;
            }
            case CROP_MSG: {
                if (resultCode == RESULT_OK) {
                    setResult(resultCode, data);
                    finish();
                }
                break;
            }
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        mPausing = true;

        mLoader.stop();

        mGvs.stop();

        if (mReceiver != null) {
            unregisterReceiver(mReceiver);
            mReceiver = null;
        }

        // Now that we've paused the threads that are using the cursor it is
        // safe to close it.
        mAllImages.close();
        mAllImages = null;
    }

    private void rebake(boolean unmounted, boolean scanning) {
        mGvs.stop();
        if (mAllImages != null) {
            mAllImages.close();
            mAllImages = null;
        }

        if (mMediaScanningDialog != null) {
            mMediaScanningDialog.cancel();
            mMediaScanningDialog = null;
        }

        if (scanning) {
            mMediaScanningDialog = ProgressDialog.show(
                    this,
                    null,
                    getResources().getString(R.string.wait),
                    true,
                    true);
        }

        mParam = allImages(!unmounted && !scanning);
        mAllImages = ImageManager.makeImageList(getContentResolver(), mParam);

        mGvs.setImageList(mAllImages);
        mGvs.setDrawAdapter(this);
        mGvs.setLoader(mLoader);
        mGvs.start();
        mNoImagesView.setVisibility(mAllImages.getCount() > 0
                ? View.GONE
                : View.VISIBLE);
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putFloat(STATE_SCROLL_POSITION, mScrollPosition);
        state.putInt(STATE_SELECTED_INDEX, mSelectedIndex);
    }

    @Override
    protected void onRestoreInstanceState(Bundle state) {
        super.onRestoreInstanceState(state);
        mScrollPosition = state.getFloat(
                STATE_SCROLL_POSITION, INVALID_POSITION);
        mSelectedIndex = state.getInt(STATE_SELECTED_INDEX, 0);
    }

    @Override
    public void onResume() {
        super.onResume();

        mGvs.setSizeChoice(Integer.parseInt(
                mPrefs.getString("pref_gallery_size_key", "1")));
        mGvs.requestFocus();

        String sortOrder = mPrefs.getString("pref_gallery_sort_key", null);
        if (sortOrder != null) {
            mSortAscending = sortOrder.equals("ascending");
        }

        mPausing = false;

        // install an intent filter to receive SD card related events.
        IntentFilter intentFilter =
                new IntentFilter(Intent.ACTION_MEDIA_MOUNTED);
        intentFilter.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
        intentFilter.addAction(Intent.ACTION_MEDIA_SCANNER_STARTED);
        intentFilter.addAction(Intent.ACTION_MEDIA_SCANNER_FINISHED);
        intentFilter.addAction(Intent.ACTION_MEDIA_EJECT);
        intentFilter.addDataScheme("file");

        mReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (action.equals(Intent.ACTION_MEDIA_MOUNTED)) {
                    // SD card available
                    // TODO put up a "please wait" message
                    // TODO also listen for the media scanner finished message
                } else if (action.equals(Intent.ACTION_MEDIA_UNMOUNTED)) {
                    // SD card unavailable
                    rebake(true, false);
                } else if (action.equals(Intent.ACTION_MEDIA_SCANNER_STARTED)) {
                    rebake(false, true);
                } else if (action.equals(
                        Intent.ACTION_MEDIA_SCANNER_FINISHED)) {
                    rebake(false, false);
                } else if (action.equals(Intent.ACTION_MEDIA_EJECT)) {
                    rebake(true, false);
                }
            }
        };
        registerReceiver(mReceiver, intentFilter);
        rebake(false, ImageManager.isMediaScannerScanning(
                getContentResolver()));
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        if (isPickIntent()) {
            String type = getIntent().resolveType(this);
            if (type != null) {
                if (isImageType(type)) {
                } else if (isVideoType(type)) {
                }
            }
        } else {
            if ((mInclusion & ImageManager.INCLUDE_IMAGES) != 0) {
                mSlideShowItem = addSlideShowMenu(menu);
            }

            MenuItem item = menu.add(Menu.NONE, Menu.NONE,
                    MenuHelper.POSITION_GALLERY_SETTING,
                    R.string.camerasettings);
            item.setOnMenuItemClickListener(
                    new MenuItem.OnMenuItemClickListener() {
                public boolean onMenuItemClick(MenuItem item) {
                    Intent preferences = new Intent();
                    preferences.setClass(ImageGallery.this,
                            GallerySettings.class);
                    startActivity(preferences);
                    return true;
                }
            });
            item.setAlphabeticShortcut('p');
            item.setIcon(android.R.drawable.ic_menu_preferences);

            item = menu.add(Menu.NONE, Menu.NONE,
                    MenuHelper.POSITION_MULTISELECT,
                    R.string.multiselect);
            item.setOnMenuItemClickListener(
                    new MenuItem.OnMenuItemClickListener() {
                public boolean onMenuItemClick(MenuItem item) {
                    if (isInMultiSelectMode()) {
                        closeMultiSelectMode();
                    } else {
                        openMultiSelectMode();
                    }
                    return true;
                }
            });
            item.setIcon(R.drawable.ic_menu_multiselect_gallery);
        }
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        if (!canHandleEvent()) return false;
        if ((mInclusion & ImageManager.INCLUDE_IMAGES) != 0) {
            boolean videoSelected = isVideoSelected();
            // TODO: Only enable slide show if there is at least one image in
            // the folder.
            if (mSlideShowItem != null) {
                mSlideShowItem.setEnabled(!videoSelected);
            }
        }

        return true;
    }

    private boolean isVideoSelected() {
        IImage image = getCurrentImage();
        return (image != null) && ImageManager.isVideo(image);
    }

    private boolean isImageType(String type) {
        return type.equals("vnd.android.cursor.dir/image")
                || type.equals("image/*");
    }

    private boolean isVideoType(String type) {
        return type.equals("vnd.android.cursor.dir/video")
                || type.equals("video/*");
    }

    // According to the intent, setup what we include (image/video) in the
    // gallery and the title of the gallery.
    private void setupInclusion() {
        mInclusion = ImageManager.INCLUDE_IMAGES | ImageManager.INCLUDE_VIDEOS;

        Intent intent = getIntent();
        if (intent != null) {
            String type = intent.resolveType(this);
            TextView leftText = (TextView) findViewById(R.id.left_text);
            if (type != null) {
                if (isImageType(type)) {
                    mInclusion = ImageManager.INCLUDE_IMAGES;
                    if (isPickIntent()) {
                        leftText.setText(R.string.pick_photos_gallery_title);
                    } else {
                        leftText.setText(R.string.photos_gallery_title);
                    }
                }
                if (isVideoType(type)) {
                    mInclusion = ImageManager.INCLUDE_VIDEOS;
                    if (isPickIntent()) {
                        leftText.setText(R.string.pick_videos_gallery_title);
                    } else {
                        leftText.setText(R.string.videos_gallery_title);
                    }
                }
            }
            Bundle extras = intent.getExtras();
            String title = (extras != null)
                    ? extras.getString("windowTitle")
                    : null;
            if (title != null && title.length() > 0) {
                leftText.setText(title);
            }

            if (extras != null) {
                mInclusion = (ImageManager.INCLUDE_IMAGES
                        | ImageManager.INCLUDE_VIDEOS)
                        & extras.getInt("mediaTypes", mInclusion);
            }

            if (extras != null && extras.getBoolean("pick-drm")) {
                Log.d(TAG, "pick-drm is true");
                mInclusion = ImageManager.INCLUDE_DRM_IMAGES;
            }
        }
    }

    // Returns the image list parameter which contains the subset of image/video
    // we want.
    private ImageManager.ImageListParam allImages(boolean storageAvailable) {
        if (!storageAvailable) {
            return ImageManager.getEmptyImageListParam();
        } else {
            Uri uri = getIntent().getData();
            return ImageManager.getImageListParam(
                    ImageManager.DataLocation.EXTERNAL,
                    mInclusion,
                    mSortAscending
                    ? ImageManager.SORT_ASCENDING
                    : ImageManager.SORT_DESCENDING,
                    (uri != null)
                    ? uri.getQueryParameter("bucketId")
                    : null);
        }
    }

    private void toggleMultiSelected(IImage image) {
        int original = mMultiSelected.size();
        if (!mMultiSelected.add(image)) {
            mMultiSelected.remove(image);
        }
        mGvs.invalidate();
        if (original == 0) showFooter();
        if (mMultiSelected.size() == 0) hideFooter();
    }

    public void onImageClicked(int index) {
        if (index < 0 || index >= mAllImages.getCount()) {
            return;
        }
        mSelectedIndex = index;
        mGvs.setSelectedIndex(index);

        IImage image = mAllImages.getImageAt(index);

        if (isInMultiSelectMode()) {
            toggleMultiSelected(image);
            return;
        }

        if (isPickIntent()) {
            launchCropperOrFinish(image);
        } else {
            Intent intent;
            if (image instanceof VideoObject) {
                intent = new Intent(
                        Intent.ACTION_VIEW, image.fullSizeImageUri());
                intent.putExtra(MediaStore.EXTRA_SCREEN_ORIENTATION,
                        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            } else {
                intent = new Intent(this, ViewImage.class);
                intent.putExtra(ViewImage.KEY_IMAGE_LIST, mParam);
                intent.setData(image.fullSizeImageUri());
            }
            startActivity(intent);
        }
    }

    public void onImageTapped(int index) {
        // In the multiselect mode, once the finger finishes tapping, we hide
        // the selection box by setting the selected index to none. However, if
        // we use the dpad center key, we will keep the selected index in order
        // to show the the selection box. We do this because we have the
        // multiselect marker on the images to indicate which of them are
        // selected, so we don't need the selection box, but in the dpad case
        // we still need the selection box to show as a "cursor".

        if (isInMultiSelectMode()) {
            mGvs.setSelectedIndex(GridViewSpecial.INDEX_NONE);
            toggleMultiSelected(mAllImages.getImageAt(index));
        } else {
            onImageClicked(index);
        }
    }

    private class CreateContextMenuListener implements
            View.OnCreateContextMenuListener {
        public void onCreateContextMenu(ContextMenu menu, View v,
                ContextMenu.ContextMenuInfo menuInfo) {
            if (!canHandleEvent()) return;

            IImage image = getCurrentImage();

            if (image == null) {
                return;
            }

            boolean isImage = ImageManager.isImage(image);
            if (isImage) {
                menu.add(R.string.view)
                        .setOnMenuItemClickListener(
                        new MenuItem.OnMenuItemClickListener() {
                            public boolean onMenuItemClick(MenuItem item) {
                                if (!canHandleEvent()) return false;
                                onImageClicked(mGvs.getCurrentSelection());
                                return true;
                            }
                        });
            }

            menu.setHeaderTitle(isImage
                    ? R.string.context_menu_header
                    : R.string.video_context_menu_header);
            if ((mInclusion & (ImageManager.INCLUDE_IMAGES
                    | ImageManager.INCLUDE_VIDEOS)) != 0) {
                MenuHelper.MenuItemsResult r = MenuHelper.addImageMenuItems(
                        menu,
                        MenuHelper.INCLUDE_ALL,
                        ImageGallery.this,
                        mHandler,
                        mDeletePhotoRunnable,
                        new MenuHelper.MenuInvoker() {
                            public void run(MenuHelper.MenuCallback cb) {
                                if (!canHandleEvent()) {
                                    return;
                                }
                                cb.run(getCurrentImageUri(), getCurrentImage());
                                mGvs.invalidateImage(mGvs.getCurrentSelection());
                            }
                        });

                if (r != null) {
                    r.gettingReadyToOpen(menu, image);
                }

                if (isImage) {
                    addSlideShowMenu(menu);
                }
            }
        }
    }

    public void onLayoutComplete(boolean changed) {
        mLayoutComplete = true;
        if (mCropResultUri != null) {
            IImage image = mAllImages.getImageForUri(mCropResultUri);
            mCropResultUri = null;
            if (image != null) {
                mSelectedIndex = mAllImages.getImageIndex(image);
            }
        }
        mGvs.setSelectedIndex(mSelectedIndex);
        if (mScrollPosition == INVALID_POSITION) {
            if (mSortAscending) {
                mGvs.scrollTo(0, mGvs.getHeight());
            } else {
                mGvs.scrollToImage(0);
            }
        } else if (mConfigurationChanged) {
            mConfigurationChanged = false;
            mGvs.scrollTo(mScrollPosition);
            if (mGvs.getCurrentSelection() != GridViewSpecial.INDEX_NONE) {
                mGvs.scrollToVisible(mSelectedIndex);
            }
        } else {
            mGvs.scrollTo(mScrollPosition);
        }
    }

    public void onScroll(float scrollPosition) {
        mScrollPosition = scrollPosition;
    }

    private Drawable mVideoOverlay;
    private Drawable mVideoMmsErrorOverlay;
    private Drawable mMultiSelectTrue;
    private Drawable mMultiSelectFalse;

    // mSrcRect and mDstRect are only used in drawImage, but we put them as
    // instance variables to reduce the memory allocation overhead because
    // drawImage() is called a lot.
    private final Rect mSrcRect = new Rect();
    private final Rect mDstRect = new Rect();

    private final Paint mPaint = new Paint(Paint.FILTER_BITMAP_FLAG);

    public void drawImage(Canvas canvas, IImage image,
            Bitmap b, int xPos, int yPos, int w, int h) {
        if (b != null) {
            // if the image is close to the target size then crop,
            // otherwise scale both the bitmap and the view should be
            // square but I suppose that could change in the future.

            int bw = b.getWidth();
            int bh = b.getHeight();

            int deltaW = bw - w;
            int deltaH = bh - h;

            if (deltaW >= 0 && deltaW < 10 &&
                deltaH >= 0 && deltaH < 10) {
                int halfDeltaW = deltaW / 2;
                int halfDeltaH = deltaH / 2;
                mSrcRect.set(0 + halfDeltaW, 0 + halfDeltaH,
                        bw - halfDeltaW, bh - halfDeltaH);
                mDstRect.set(xPos, yPos, xPos + w, yPos + h);
                canvas.drawBitmap(b, mSrcRect, mDstRect, null);
            } else {
                mSrcRect.set(0, 0, bw, bh);
                mDstRect.set(xPos, yPos, xPos + w, yPos + h);
                canvas.drawBitmap(b, mSrcRect, mDstRect, mPaint);
            }
        } else {
            // If the thumbnail cannot be drawn, put up an error icon
            // instead
            Bitmap error = getErrorBitmap(image);
            int width = error.getWidth();
            int height = error.getHeight();
            mSrcRect.set(0, 0, width, height);
            int left = (w - width) / 2 + xPos;
            int top = (w - height) / 2 + yPos;
            mDstRect.set(left, top, left + width, top + height);
            canvas.drawBitmap(error, mSrcRect, mDstRect, null);
        }

        if (ImageManager.isVideo(image)) {
            Drawable overlay = null;
            long size = MenuHelper.getImageFileSize(image);
            if (size >= 0 && size <= mVideoSizeLimit) {
                if (mVideoOverlay == null) {
                    mVideoOverlay = getResources().getDrawable(
                            R.drawable.ic_gallery_video_overlay);
                }
                overlay = mVideoOverlay;
            } else {
                if (mVideoMmsErrorOverlay == null) {
                    mVideoMmsErrorOverlay = getResources().getDrawable(
                            R.drawable.ic_error_mms_video_overlay);
                }
                overlay = mVideoMmsErrorOverlay;
                Paint paint = new Paint();
                paint.setARGB(0x80, 0x00, 0x00, 0x00);
                canvas.drawRect(xPos, yPos, xPos + w, yPos + h, paint);
            }
            int width = overlay.getIntrinsicWidth();
            int height = overlay.getIntrinsicHeight();
            int left = (w - width) / 2 + xPos;
            int top = (h - height) / 2 + yPos;
            mSrcRect.set(left, top, left + width, top + height);
            overlay.setBounds(mSrcRect);
            overlay.draw(canvas);
        }
    }

    public boolean needsDecoration() {
        return (mMultiSelected != null);
    }

    public void drawDecoration(Canvas canvas, IImage image,
            int xPos, int yPos, int w, int h) {
        if (mMultiSelected != null) {
            initializeMultiSelectDrawables();

            Drawable checkBox = mMultiSelected.contains(image)
                    ? mMultiSelectTrue
                    : mMultiSelectFalse;
            int width = checkBox.getIntrinsicWidth();
            int height = checkBox.getIntrinsicHeight();
            int left = 5 + xPos;
            int top = h - height - 5 + yPos;
            mSrcRect.set(left, top, left + width, top + height);
            checkBox.setBounds(mSrcRect);
            checkBox.draw(canvas);
        }
    }

    private void initializeMultiSelectDrawables() {
        if (mMultiSelectTrue == null) {
            mMultiSelectTrue = getResources()
                    .getDrawable(R.drawable.btn_check_buttonless_on);
        }
        if (mMultiSelectFalse == null) {
            mMultiSelectFalse = getResources()
                    .getDrawable(R.drawable.btn_check_buttonless_off);
        }
    }

    private Bitmap mMissingImageThumbnailBitmap;
    private Bitmap mMissingVideoThumbnailBitmap;

    // Create this bitmap lazily, and only once for all the ImageBlocks to
    // use
    public Bitmap getErrorBitmap(IImage image) {
        if (ImageManager.isImage(image)) {
            if (mMissingImageThumbnailBitmap == null) {
                mMissingImageThumbnailBitmap = BitmapFactory.decodeResource(
                        getResources(),
                        R.drawable.ic_missing_thumbnail_picture);
            }
            return mMissingImageThumbnailBitmap;
        } else {
            if (mMissingVideoThumbnailBitmap == null) {
                mMissingVideoThumbnailBitmap = BitmapFactory.decodeResource(
                        getResources(), R.drawable.ic_missing_thumbnail_video);
            }
            return mMissingVideoThumbnailBitmap;
        }
    }

    private Animation mFooterAppear;
    private Animation mFooterDisappear;

    private void showFooter() {
        mFooterOrganizeView.setVisibility(View.VISIBLE);
        if (mFooterAppear == null) {
            mFooterAppear = AnimationUtils.loadAnimation(
                    this, R.anim.footer_appear);
        }
        mFooterOrganizeView.startAnimation(mFooterAppear);
    }

    private void hideFooter() {
        if (mFooterOrganizeView.getVisibility() != View.GONE) {
            mFooterOrganizeView.setVisibility(View.GONE);
            if (mFooterDisappear == null) {
                mFooterDisappear = AnimationUtils.loadAnimation(
                        this, R.anim.footer_disappear);
            }
            mFooterOrganizeView.startAnimation(mFooterDisappear);
        }
    }

    private String getShareMultipleMimeType() {
        final int FLAG_IMAGE = 1, FLAG_VIDEO = 2;
        int flag = 0;
        for (IImage image : mMultiSelected) {
            flag |= ImageManager.isImage(image) ? FLAG_IMAGE : FLAG_VIDEO;
        }
        return flag == FLAG_IMAGE
                ? "image/*"
                : flag == FLAG_VIDEO ? "video/*" : "*/*";
    }

    private void onDeleteMultipleClicked() {
        if (mMultiSelected == null) return;
        Runnable action = new Runnable() {
            public void run() {
                ArrayList<Uri> uriList = new ArrayList<Uri>();
                ArrayList<String> pathList = new ArrayList<String>();
                for (IImage image : mMultiSelected) {
                    uriList.add(image.fullSizeImageUri());
                    pathList.add(image.getDataPath());
                }
                closeMultiSelectMode();
                Intent intent = new Intent(ImageGallery.this,
                        DeleteImage.class);
                intent.putExtra("delete-uris", uriList);
                intent.putStringArrayListExtra("delete-paths", pathList);
                try {
                    startActivity(intent);
                } catch (ActivityNotFoundException ex) {
                    Log.e(TAG, "Delete images fail", ex);
                }
            }
        };
        MenuHelper.deleteMultiple(this, action);
    }

    private boolean isInMultiSelectMode() {
        return mMultiSelected != null;
    }

    private void closeMultiSelectMode() {
        if (mMultiSelected == null) return;
        mMultiSelected = null;
        mGvs.invalidate();
        hideFooter();
    }

    private void openMultiSelectMode() {
        if (mMultiSelected != null) return;
        mMultiSelected = new HashSet<IImage>();
        mGvs.invalidate();
    }

}
