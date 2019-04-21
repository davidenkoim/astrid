package model

import com.intellij.psi.PsiMethod
import downloader.Downloader.beamSubDir
import downloader.Downloader.dictSubDir
import downloader.Downloader.getModelPath
import downloader.Downloader.modelSubDir
import org.tensorflow.SavedModelBundle
import org.tensorflow.Session
import org.tensorflow.Tensor
import helpers.TensorConverter.parsePredictions
import helpers.TensorConverter.parseScores
import inspections.Suggestion
import net.razorvine.pickle.Unpickler
import org.tensorflow.TensorFlow
import utils.PathUtils.getCombinedPaths
import utils.PsiUtils
import java.io.FileInputStream
import java.util.*
import kotlin.collections.ArrayList

class ModelFacade {

    companion object {
        private val beamSearchModule = TensorFlow.loadLibrary(getModelPath().toString() + modelSubDir + beamSubDir);
        private val tfModel: SavedModelBundle = SavedModelBundle.load(getModelPath().toString() + modelSubDir, "serve")
    }

    fun getSuggestions(method: PsiMethod): Suggestion {
        val methodBody = PsiUtils.getMethodBody(method)
        return Suggestion(generatePredictions(methodBody))
    }

    fun getSuggestions(methodBody: String): Suggestion {
        return Suggestion(generatePredictions(methodBody))
    }

    private fun parseResults(listOfIndexes: List<List<Any>>): List<String> {
        var predictions = ArrayList<String>()
        val stream = FileInputStream(getModelPath().toString() + dictSubDir)
        val unpickler = Unpickler()
        val dictionary = unpickler.load(stream)
        val mapOfSubtokens = dictionary as HashMap<Int, String>
        for (indexes in listOfIndexes) {
            var subtoken = mapOfSubtokens.get(indexes[0]) ?: ""
            for (i in indexes.subList(1, indexes.size)) {
                var word: String? = mapOfSubtokens.get(i)
                if (word != null && !word.equals("<PAD>") && !word.equals("<UNK>"))
                    subtoken += word.substring(0, 1).toUpperCase() + word.substring(1)
            }
            predictions.add(subtoken)
        }
        return predictions
    }

    private fun generatePredictions(methodBody: String): ArrayList<Pair<String, Double>> {
        val paths = getCombinedPaths(methodBody)
        val session: Session = tfModel.session()
        val runnerForNames = session.runner()
        val runnerForScores = session.runner()
        val inputTensor = Tensor.create(paths.toByteArray(Charsets.UTF_8), String::class.java)

        val outputTensorForNames: Tensor<*> = runnerForNames.feed("Placeholder:0", inputTensor).fetch("model/decoder/transpose:0").run()[0]
        val predictions: List<List<Any>> = parsePredictions(outputTensorForNames) as List<List<Any>>
        val parsedPredictions: List<String> = parseResults(predictions)

        val outputTensorScores: Tensor<*> = runnerForScores.feed("Placeholder:0", inputTensor).fetch("model/decoder/transpose_1:0").run()[0]
        val scores: List<Double> = parseScores(outputTensorScores) as List<Double>

        val resultPairs: ArrayList<Pair<String, Double>> = ArrayList()
        for (i in 0 until parsedPredictions.size) {
            if (parsedPredictions[i].isNotEmpty()
                    && !parsedPredictions[i].equals("<UNK>") && !parsedPredictions[i].equals("<PAD>")) {
                resultPairs.add(Pair(parsedPredictions[i], scores[i]))
            }
        }

        outputTensorForNames.close()
        outputTensorScores.close()
        return resultPairs
    }
}