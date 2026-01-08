import cv2
import numpy as np
import tensorflow as tf
from gtts import gTTS
import pygame
import os
import time

def say(text):
    tts = gTTS(text=text, lang='en')
    filename = "temp_audio.mp3"
    tts.save(filename)

    pygame.mixer.init()
    pygame.mixer.music.load(filename)
    pygame.mixer.music.play()

    while pygame.mixer.music.get_busy():
        time.sleep(0.1)

    pygame.mixer.music.stop()  # Stop mixer before deleting file
    pygame.mixer.quit()        # Quit mixer

    os.remove(filename)

# Rebuild the model architecture
base_model = tf.keras.applications.MobileNetV2(input_shape=(224, 224, 3),
                                               include_top=False,
                                               weights='imagenet')
base_model.trainable = False

model = tf.keras.Sequential([
    base_model,
    tf.keras.layers.GlobalAveragePooling2D(),
    tf.keras.layers.Dense(3, activation='softmax')
])

# Load saved weights (update path if needed)
model.load_weights(r"D:\ItemDetection\model_weights.weights.h5")

class_names = ["rock", "stairs", "wall"]

print("Starting webcam...")
cap = cv2.VideoCapture(0)
if not cap.isOpened():
    print("Cannot open camera")
    exit()
print("Camera opened successfully.")

last_class_id = None  # Track last announced class

while True:
    ret, frame = cap.read()
    if not ret:
        print("Failed to grab frame")
        break

    print("Got frame, running prediction...")

    img = cv2.resize(frame, (224, 224))
    img = img / 255.0  # Normalize as done during training
    img = np.expand_dims(img, axis=0)

    preds = model.predict(img)
    class_id = np.argmax(preds)
    confidence = preds[0][class_id]
    label = f"{class_names[class_id]}: {confidence:.2f}"

    cv2.putText(frame, label, (10, 30),
                cv2.FONT_HERSHEY_SIMPLEX, 1, (0, 255, 0), 2)
    cv2.imshow("Object Detection", frame)

    # Announce only if detected class changed
    if class_id != last_class_id:
        if class_names[class_id] == "stairs":
            say("Climb the stairs")
        else:
            say(class_names[class_id])
        last_class_id = class_id

    if cv2.waitKey(1) & 0xFF == ord('q'):
        break

cap.release()
cv2.destroyAllWindows()
