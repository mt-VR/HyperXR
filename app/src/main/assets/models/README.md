# hand_landmarker.task

MediaPipe Hand Landmarker, float16 bundle (hand_detector.tflite + hand_landmarks_detector.tflite).

- Source: https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/latest/hand_landmarker.task
- Published: 2023-04-26 (Last-Modified of the download), 7,819,105 bytes
- License: Apache License 2.0 (MediaPipe models, see https://ai.google.dev/edge/mediapipe/solutions/vision/hand_landmarker#models)
- Model card: https://storage.googleapis.com/mediapipe-assets/Model%20Card%20Hand%20Tracking%20(Lite_Full)%20with%20Fairness%20Oct%202021.pdf

# rtm_hand_detector.onnx / rtm_hand_pose.tflite

OpenMMLab hand models (MMPose RTMPose project), License: Apache License 2.0
(https://github.com/open-mmlab/mmpose/blob/main/LICENSE).

- `rtm_hand_detector.onnx`: RTMDet-nano hand detector, the `end2end.onnx` of
  https://download.openmmlab.com/mmpose/v1/projects/rtmposev1/onnx_sdk/rtmdet_nano_8xb32-300e_hand-267f9c8f.zip
  (unchanged; 320x320 input, NMS inside).
- `rtm_hand_pose.tflite`: RTMPose-m hand (hand5 dataset, 256x256, SimCC), from the `end2end.onnx` of
  https://download.openmmlab.com/mmpose/v1/projects/rtmposev1/onnx_sdk/rtmpose-m_simcc-hand5_pt-aic-coco_210e-256x256-74fb594_20230320.zip,
  batch fixed to 1, simplified (onnxsim) and converted with onnx2tf (`-tb tf_converter`) to TFLite
  with float16 weights, NHWC input. Checked against the ONNX: fp32 max difference 5e-6; fp16 picks
  the same SimCC bin for 95-100% of the joints on a real photo.

# arm_segmenter.tflite

MediaPipe Selfie Multiclass Segmentation (256x256, float32), used to see the forearm: its
"body-skin" and "clothes" classes mark the arm in the camera picture, run on the NPU (NNAPI).

- Source: https://storage.googleapis.com/mediapipe-models/image_segmenter/selfie_multiclass_256x256/float32/latest/selfie_multiclass_256x256.tflite
- Published: 2023-04-26 (Last-Modified of the download), 16,371,837 bytes, unchanged
- License: Apache License 2.0 (MediaPipe models, see https://ai.google.dev/edge/mediapipe/solutions/vision/image_segmenter#multiclass-model)
- Input 1x256x256x3 RGB, (pixel - 127.5) / 127.5; output 1x256x256x6: background, hair, body-skin, face-skin, clothes, others

# hand_skin.bin

The skinned human arm and hand of the VR mode, made by `tools/make_hand_mesh.py` (HyperXR's own,
from a signed distance field; no third-party art).
