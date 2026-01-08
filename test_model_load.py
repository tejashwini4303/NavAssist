import tensorflow as tf

# Rebuild your model architecture exactly as during training
base_model = tf.keras.applications.MobileNetV2(input_shape=(224, 224, 3),
                                               include_top=False,
                                               weights='imagenet')
base_model.trainable = False

model = tf.keras.Sequential([
    base_model,
    tf.keras.layers.GlobalAveragePooling2D(),
    tf.keras.layers.Dense(3, activation='softmax')
])

# Load weights from your .keras file (weights file needed, not full model)
model.load_weights(r"D:\ItemDetection\my_model.keras")

print("Model loaded successfully!")
