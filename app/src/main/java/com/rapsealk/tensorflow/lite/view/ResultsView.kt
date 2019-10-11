package com.rapsealk.tensorflow.lite.view

import com.rapsealk.tensorflow.lite.tflite.Classifier

interface ResultsView {
    fun setResults(results: List<Classifier.Companion.Recognition>)
}