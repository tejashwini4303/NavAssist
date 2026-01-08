from gtts import gTTS
from playsound import playsound
import os

def say(text):
    tts = gTTS(text=text, lang='en')
    filename = "temp_audio.mp3"
    tts.save(filename)
    playsound(filename)
    os.remove(filename)
