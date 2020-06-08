/*
 * Copyright 2019 The TensorFlow Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.tensorflow.lite.examples.detection;

import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.Size;
import android.widget.Toast;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.tensorflow.lite.examples.detection.env.ImageUtils;
import org.tensorflow.lite.examples.detection.tflite.Classifier;
import org.tensorflow.lite.examples.detection.tflite.TFLiteObjectDetectionAPIModel;

/**
 * An activity that uses a TensorFlowMultiBoxDetector and ObjectTracker to detect and then track
 * objects.
 */
public class DetectorActivity extends CameraXActivity {
    // SSD-Model configuration
    private static final int w = 360;
    private static final int h = 640;
    private static final int w2 = 600;
    private static final int h2 = 600;
    private static final boolean TF_IS_QUANTIZED = false;
    private static final String TF_BOX_MODEL = "box.tflite";
    private static final String TF_BOX_LABELS = "file:///android_asset/labels_box.txt";
    private static final String TF_DIGIT_MODEL = "numbers.tflite";
    private static final String TF_DIGIT_LABELS = "file:///android_asset/labels.txt";
    private static final float MINIMUM_CONFIDENCE = 0.4f;
    private static final boolean MAINTAIN_ASPECT = false;
    private static int tagRotation;

    private Classifier box_detector;
    private Classifier digit_detector;

    private Bitmap box_bitmap = null;
    private Bitmap digit_bitmap = null;

    private boolean computingDetection = false;

    private Matrix frameToCropTransform;
    private Matrix cropToFrameTransform;

    @Override
    public void initDetector() {
        try {
            box_detector = TFLiteObjectDetectionAPIModel.create(
                    getAssets(),
                    TF_BOX_MODEL,
                    TF_BOX_LABELS,
                    w,
                    h,
                    TF_IS_QUANTIZED);
            digit_detector = TFLiteObjectDetectionAPIModel.create(
                    getAssets(),
                    TF_DIGIT_MODEL,
                    TF_DIGIT_LABELS,
                    w2,
                    h2,
                    TF_IS_QUANTIZED);
        } catch (final IOException e) {
            e.printStackTrace();
            Toast toast = Toast.makeText(
                    getApplicationContext(), "Classifier could not be initialized", Toast.LENGTH_SHORT);
            toast.show();
            finish();
        }

        box_bitmap = Bitmap.createBitmap(w, h, Config.ARGB_8888);
        digit_bitmap = Bitmap.createBitmap(w2, h2, Config.ARGB_8888);

        frameToCropTransform = ImageUtils.getTransformationMatrix(
                        previewWidth, previewHeight,
                        //cropSize, cropSize,
                        w, h,
                        0, MAINTAIN_ASPECT
        );

        cropToFrameTransform = new Matrix();
        frameToCropTransform.invert(cropToFrameTransform);
    }

    @Override
    protected void processImage() {

        // Skip requests while computing detection
        if (computingDetection) return;

        // Start detection process
        computingDetection = true;

        final Canvas canvas = new Canvas(box_bitmap);
        canvas.drawBitmap(rgbFrameBitmap, frameToCropTransform, null);

        runInBackground(() -> {
            runOnUiThread(() -> setPredictionView("Führe Erkennung durch..."));

            // returns list sorted by confidence
            final List<Classifier.Recognition> detected_boxes = box_detector.recognizeImage(box_bitmap);

            // case: nothing detected
            if (detected_boxes.isEmpty() || detected_boxes.get(0).getConfidence() < MINIMUM_CONFIDENCE) {
                runOnUiThread(() -> setPredictionView("Erkannt: Nichts"));
                computingDetection = false;
                return;
            }

            final Classifier.Recognition detected_box = getDetectedBox(detected_boxes);
            cropDetectedBox(detected_box);

            final List<Classifier.Recognition> detected_digits = digit_detector.recognizeImage(digit_bitmap);

            // keep results with minimum confidence
            ArrayList<Classifier.Recognition> res = new ArrayList<>();
            for (final Classifier.Recognition result : detected_digits)
                if (result.getConfidence() >= MINIMUM_CONFIDENCE)
                    res.add(result);

            // return if less than 3 numbers detected
            if (res.size() < 3) {
                runOnUiThread(() -> setPredictionView("Erkannt: Nichts"));
                //switchBackToCallerActivity(new ArrayList<>());
                return;
            }

            // TODO remove boxes with same location
            // TODO not working for two hidden small numbers
            ArrayList<String> return_res = getSmallDigits(res);
            createReturnString(return_res, res);

            // printing prediction on view
            runOnUiThread(() -> setPredictionView("Erkannt: " + return_res.toString()));
            computingDetection = false;
            //switchBackToCallerActivity(return_res);
        });
    }

    /**
     * @param detected_box stores detected part of image into bitmap
     */
    private void cropDetectedBox(Classifier.Recognition detected_box) {
        Rect rect = new Rect(
                (int) detected_box.getLocation().left,
                (int) detected_box.getLocation().top,
                (int) detected_box.getLocation().right,
                (int) detected_box.getLocation().bottom
        );

        expandToSquare(rect);

        //  create our resulting bitmap and draw source into it
        Bitmap tagBitmap = Bitmap.createBitmap(rect.right - rect.left, rect.bottom - rect.top, Bitmap.Config.ARGB_8888);
        new Canvas(tagBitmap).drawBitmap(rgbFrameBitmap, -rect.left, -rect.top, null);

        // rotation + 90 caused by portrait mode
        Matrix frameToBoxTransform = ImageUtils.getTransformationMatrix(
                tagBitmap.getWidth(), tagBitmap.getHeight(), w2, h2,
                getOrientationDegrees() + tagRotation, MAINTAIN_ASPECT);
        frameToBoxTransform.invert(new Matrix());

        // draw transformed bitmap onto croppedBitmap
        new Canvas(digit_bitmap).drawBitmap(tagBitmap, frameToBoxTransform, null);
    }

    /**
     * @param r adjust box coordinates to crop a square for better detection
     */
    private void expandToSquare(Rect r) {
        // detection caused coordinate rotation by 90 deg clockwise, width <-> height etc..
        int delta = (r.bottom - r.top) - (r.right - r.left);
        int upperBound = rgbFrameBitmap.getWidth();
        int min = r.left;
        int max = r.right;
        tagRotation = 90;

        // width smaller than height
        if (delta < 0) {
            tagRotation = 0;
            upperBound = rgbFrameBitmap.getHeight();
            delta = -delta;
            min = r.top;
            max = r.bottom;
        }

        if (max + delta < upperBound)
            max += delta;
        else {
            delta -= upperBound - max;
            max = upperBound;
            if (min - delta > 0)
                min -= delta;
            else
                min = 0;
        }

        if (upperBound == rgbFrameBitmap.getHeight()) {
            r.top = min;
            r.bottom = max;
        } else {
            r.left = min;
            r.right = max;
        }
    }

    /**
     * @param res detected results
     * @return box with mapped location
     */
    private Classifier.Recognition getDetectedBox(List<Classifier.Recognition> res) {
        Classifier.Recognition detected_box = res.get(0);
        RectF location = detected_box.getLocation();
        cropToFrameTransform.mapRect(location);
        detected_box.setLocation(location);
        return detected_box;
    }

    private void sortDigitsByX(ArrayList<Classifier.Recognition> al) {
        Collections.sort(al, (r1, r2) ->
                Float.compare(
                        r1.getLocation().centerX(),
                        r2.getLocation().centerX()
                )
        );
    }

    /**
     * sort recognized items by vertical position weighted by
     * digitHeight*2 to detect the small numbers first
     */
    private void sortDigitsByYAndHeight(ArrayList<Classifier.Recognition> al) {
        Collections.sort(al, (r1, r2) ->
                Float.compare(
                        r1.getLocation().centerY() + r1.getLocation().height() * 2,
                        r2.getLocation().centerY() + r2.getLocation().height() * 2
                )
        );
    }

    private ArrayList<String> getSmallDigits(ArrayList<Classifier.Recognition> res) {
        ArrayList<String> ret_string = new ArrayList<>();

        sortDigitsByYAndHeight(res);
        float num_height = res.get(0).getLocation().height();
        float pos1 = res.get(0).getLocation().centerY() + res.get(0).getLocation().centerX();
        float pos2 = res.get(1).getLocation().centerY() + res.get(1).getLocation().centerX();
        float pos3 = res.get(2).getLocation().centerY() + res.get(2).getLocation().centerX();

        if (Math.abs(pos1 - pos2) > num_height) {
            // one small one big number
            ret_string.add(res.remove(0).getTitle());
            ret_string.add("-");
        } else if (Math.abs(pos1 - pos3) > num_height) {
            // two small numbers
            int pos = 0;
            if (res.get(1).getLocation().centerX() < res.get(0).getLocation().centerX())
                pos = 1;
            ret_string.add(res.remove(pos).getTitle());
            ret_string.add(res.remove(0).getTitle());
        } else {
            // no small number, add two null elements for the unrecognized number
            ret_string.add("-");
            ret_string.add("-");
        }
        return ret_string;
    }

    private void createReturnString(ArrayList<String> return_res, ArrayList<Classifier.Recognition> res) {
        float comp_height = -1;
        sortDigitsByX(res);

        for (int i = 0; i < 7; i++) {
            if (res.isEmpty()) {
                return_res.add("-");
                continue;
            }

            Classifier.Recognition result = res.get(0);

            // if number height smaller than 3/4 of the previous number, it is a small number
            if (result.getLocation().height() < comp_height * 0.75) {
                if (return_res.size() < 6) {
                    return_res.add("-");
                    continue;
                }
            } // initialize the height to compare
            else if (comp_height == -1)
                comp_height = result.getLocation().height();
            return_res.add(res.remove(0).getTitle());
        }
    }
}
