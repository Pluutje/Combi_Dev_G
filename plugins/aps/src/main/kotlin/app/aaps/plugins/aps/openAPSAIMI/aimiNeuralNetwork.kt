package app.aaps.plugins.aps.openAPSAIMI

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.tanh
import kotlin.random.Random

class AimiNeuralNetwork(
    private val inputSize: Int,
    private val hiddenSize: Int,
    private val outputSize: Int,
    private val config: TrainingConfig = TrainingConfig(),
    private val regularizationLambda: Double = 0.01,
    private val dropoutRate: Double = 0.5
) {

    //private var weightsInputHidden = Array(inputSize) { DoubleArray(hiddenSize) { Random.nextDouble(-sqrt(6.0 / (inputSize + hiddenSize)), sqrt(6.0 / (inputSize + hiddenSize))) } }
    private var weightsInputHidden = Array(inputSize) {
        DoubleArray(hiddenSize) { Random.nextDouble(-sqrt(2.0 / inputSize), sqrt(2.0 / inputSize)) }
    }
    private var bestLoss: Double = Double.MAX_VALUE
    private var biasHidden = DoubleArray(hiddenSize) { 0.01 }
    private var weightsHiddenOutput = Array(hiddenSize) { DoubleArray(outputSize) { Random.nextDouble(-sqrt(6.0 / (hiddenSize + outputSize)), sqrt(6.0 / (hiddenSize + outputSize))) } }
    private var biasOutput = DoubleArray(outputSize) { 0.01 }

    private var lastTrainingException: Exception? = null
    private var trainingLossHistory: MutableList<Double> = mutableListOf()

    companion object {

        fun refineSMB(smb: Float, neuralNetwork: AimiNeuralNetwork, input: DoubleArray?): Float {
            val floatInput = input?.map { it.toFloat() }?.toFloatArray() ?: return smb
            val prediction = neuralNetwork.predict(floatInput)[0]
            return smb + prediction.toFloat()
        }

    }
    private fun activation(x: Double, type: String = "ReLU"): Double {
        return when (type) {
            "Sigmoid" -> 1.0 / (1.0 + exp(-x))
            "Tanh" -> tanh(x)
            "LeakyReLU" -> if (x > 0) x else 0.01 * x
            else -> max(0.0, x) // ReLU par défaut
        }
    }


    private fun applyDropout(layer: DoubleArray): DoubleArray {
        return layer.map { if (Random.nextDouble() > dropoutRate) it else 0.0 }.toDoubleArray()
    }

    private fun relu(x: Double) = max(0.0, x)

    private fun createNewFeature(inputData: List<FloatArray>): List<FloatArray> {
        return inputData.map { originalFeatures ->
            val newFeature = originalFeatures[0] * originalFeatures[1]
            originalFeatures + newFeature
        }
    }

    private fun zScoreNormalization(inputData: List<FloatArray>): List<FloatArray> {
        if (inputData.isEmpty()) {
            return emptyList()
        }
        val means = FloatArray(inputData.first().size) { 0f }
        val stdDevs = FloatArray(inputData.first().size) { 0f }

        inputData.forEach { features ->
            features.forEachIndexed { index, value ->
                means[index] += value
                stdDevs[index] += value * value
            }
        }

        means.indices.forEach { i ->
            means[i] /= inputData.size.toFloat()
            stdDevs[i] = sqrt(stdDevs[i] / inputData.size - means[i] * means[i])
        }

        return inputData.map { features ->
            FloatArray(features.size) { index ->
                if (stdDevs[index] != 0.0f) {
                    (features[index] - means[index]) / stdDevs[index]
                } else {
                    features[index] - means[index]
                }
            }
        }
    }
    private fun applyDropout(layer: DoubleArray, dropoutRate: Double = 0.5): DoubleArray {
        return layer.map { if (Random.nextDouble() > dropoutRate) it else 0.0 }.toDoubleArray()
    }
    private fun batchNormalization(layer: DoubleArray): DoubleArray {
        val mean = layer.average()
        val variance = layer.map { (it - mean).pow(2.0) }.average()
        return layer.map { (it - mean) / sqrt(variance + 1e-8) }.toDoubleArray()
    }



    private fun forwardPass(input: FloatArray): Pair<DoubleArray, DoubleArray> {
        val hidden = DoubleArray(hiddenSize) { i ->
            var sum = 0.0
            for (j in input.indices) {
                sum += input[j] * weightsInputHidden[j][i]
            }
            //relu(sum + biasHidden[i])
            sum + biasHidden[i]
        }
        val activatedHidden = hidden.map { activation(it, "LeakyReLU") }.toDoubleArray()
        val normalizedHidden = batchNormalization(activatedHidden)
        val hiddenWithDropout = applyDropout(normalizedHidden)

        //val hiddenWithDropout = applyDropout(hidden)

        val hidden2 = DoubleArray(hiddenSize) { i ->
            var sum = 0.0
            for (j in hiddenWithDropout.indices) {
                sum += hiddenWithDropout[j] * weightsInputHidden[j][i]
            }
            relu(sum + biasHidden[i])
        }

        val output = DoubleArray(outputSize) { i ->
            var sum = 0.0
            for (j in hidden2.indices) {
                sum += hidden2[j] * weightsHiddenOutput[j][i]
            }
            sum + biasOutput[i]
        }

        return Pair(hidden2, output)
    }

    fun predict(input: FloatArray): DoubleArray {
        return forwardPassSimplified(input).second
    }

    private fun mseLoss(output: DoubleArray, target: DoubleArray): Double {
        return output.indices.sumOf { i -> (output[i] - target[i]).pow(2.0) } / output.size
    }

    private fun l2Regularization(): Double {
        var regularizationLoss = 0.0
        weightsInputHidden.forEach { layer ->
            layer.forEach { weight ->
                regularizationLoss += weight.pow(2.0)
            }
        }
        weightsHiddenOutput.forEach { layer ->
            layer.forEach { weight ->
                regularizationLoss += weight.pow(2.0)
            }
        }
        return regularizationLoss * regularizationLambda
    }

    // fun train(
    //     inputs: List<FloatArray>,
    //     targets: List<DoubleArray>,
    //     validationRawInputs: List<FloatArray>,
    //     validationTargets: List<DoubleArray>,
    //     epochs: Int,
    //     batchSize: Float = 32.0f,
    //     patience: Int = 10
    // ) {
    //     var epochsWithoutImprovement = 0
    //     val preparedInputs = createNewFeature(inputs)
    //     val normalizedInputs = zScoreNormalization(preparedInputs)
    //     val preparedValidationInputs = createNewFeature(validationRawInputs)
    //     val normalizedValidationInputs = zScoreNormalization(preparedValidationInputs)
    //
    //     val m = Array(weightsInputHidden.size) { DoubleArray(weightsInputHidden[0].size) { 0.0 } }
    //     val v = Array(weightsInputHidden.size) { DoubleArray(weightsInputHidden[0].size) { 0.0 } }
    //     val mOutput = Array(weightsHiddenOutput.size) { DoubleArray(weightsHiddenOutput[0].size) { 0.0 } }
    //     val vOutput = Array(weightsHiddenOutput.size) { DoubleArray(weightsHiddenOutput[0].size) { 0.0 } }
    //     var t = 1
    //
    //     for (epoch in 1..epochs) {
    //         var totalLoss = 0.0
    //         try {
    //             normalizedInputs.chunked(batchSize.toInt()).zip(targets.chunked(batchSize.toInt())).forEach { (batchInputs, batchTargets) ->
    //                 batchInputs.zip(batchTargets).forEach { (input, target) ->
    //                     val output = forwardPass(input).second
    //                     totalLoss += mseLoss(output, target) + l2Regularization()
    //                     backpropagation(input, target, m, v, mOutput, vOutput)
    //                 }
    //             }
    //             trainWithAdam(normalizedInputs, targets, normalizedValidationInputs, validationTargets, epochs - epoch + 1)
    //             val averageLoss = totalLoss / normalizedInputs.size
    //             trainingLossHistory.add(averageLoss)
    //
    //             val validationLoss = validate(normalizedValidationInputs, validationTargets)
    //             println("Epoch $epoch, Training Loss: $averageLoss, Validation Loss: $validationLoss")
    //             adjustLearningRate(epoch, validationLoss)
    //             if (validationLoss < bestLoss) {
    //                 bestLoss = validationLoss
    //                 epochsWithoutImprovement = 0
    //             } else {
    //                 epochsWithoutImprovement++
    //                 if (epochsWithoutImprovement >= patience) {
    //                     println("Early stopping triggered at epoch $epoch")
    //                     break
    //                 }
    //             }
    //
    //             config.learningRate *= 0.95 // Réduction dynamique du taux d'apprentissage
    //             t++
    //         } catch (e: Exception) {
    //             lastTrainingException = e
    //             println("Exception during training at epoch $epoch: ${e.message}")
    //             break
    //         }
    //     }
    // }
    // fun train(
    //     inputs: List<FloatArray>,
    //     targets: List<DoubleArray>,
    //     validationRawInputs: List<FloatArray>,
    //     validationTargets: List<DoubleArray>,
    //     epochs: Int,
    //     batchSize: Float = 32.0f,
    //     patience: Int = 10
    // ) {
    //     if (inputs.isEmpty() || targets.isEmpty()) {
    //         println("No training data available.")
    //         return
    //     }
    //
    //     val preparedInputs = createNewFeature(inputs)
    //     val normalizedInputs = zScoreNormalization(preparedInputs)
    //     val preparedValidationInputs = createNewFeature(validationRawInputs)
    //     val normalizedValidationInputs = zScoreNormalization(preparedValidationInputs)
    //
    //     if (normalizedInputs.isEmpty() || normalizedValidationInputs.isEmpty()) {
    //         println("Preprocessing resulted in empty data. Aborting training.")
    //         return
    //     }
    //
    //     // Initialize Adam parameters
    //     val m = Array(weightsInputHidden.size) { DoubleArray(weightsInputHidden[0].size) { 0.0 } }
    //     val v = Array(weightsInputHidden.size) { DoubleArray(weightsInputHidden[0].size) { 0.0 } }
    //     val mOutput = Array(weightsHiddenOutput.size) { DoubleArray(weightsHiddenOutput[0].size) { 0.0 } }
    //     val vOutput = Array(weightsHiddenOutput.size) { DoubleArray(weightsHiddenOutput[0].size) { 0.0 } }
    //     var t = 1
    //
    //     var bestLoss = Double.MAX_VALUE
    //     var epochsWithoutImprovement = 0
    //
    //     val adjustedBatchSize = minOf(batchSize.toInt(), inputs.size)
    //     if (adjustedBatchSize == 0) {
    //         println("Batch size is too small. Aborting training.")
    //         return
    //     }
    //
    //     for (epoch in 1..epochs) {
    //         var totalLoss = 0.0
    //         try {
    //             // Batch processing
    //             normalizedInputs.chunked(adjustedBatchSize).zip(targets.chunked(adjustedBatchSize)).forEach { (batchInputs, batchTargets) ->
    //                 batchInputs.zip(batchTargets).forEach { (input, target) ->
    //                     val output = forwardPass(input).second
    //                     totalLoss += mseLoss(output, target) + l2Regularization()
    //                     backpropagation(input, target, m, v, mOutput, vOutput)
    //                 }
    //             }
    //
    //             val averageLoss = totalLoss / normalizedInputs.size
    //             trainingLossHistory.add(averageLoss)
    //
    //             // Call trainWithAdam for further refinement
    //             trainWithAdam(normalizedInputs, targets, normalizedValidationInputs, validationTargets, epochs - epoch + 1)
    //
    //             // Validation and logging
    //             val validationLoss = validate(normalizedValidationInputs, validationTargets)
    //             println("Epoch $epoch, Training Loss: $averageLoss, Validation Loss: $validationLoss")
    //             adjustLearningRate(epoch, validationLoss)
    //             // Early stopping logic
    //             if (validationLoss < bestLoss) {
    //                 bestLoss = validationLoss
    //                 epochsWithoutImprovement = 0
    //             } else {
    //                 epochsWithoutImprovement++
    //                 if (epochsWithoutImprovement >= patience) {
    //                     println("Early stopping triggered at epoch $epoch due to no improvement.")
    //                     break
    //                 }
    //             }
    //
    //             // Dynamically reduce learning rate
    //             config.learningRate *= 0.95
    //             t++
    //         } catch (e: Exception) {
    //             lastTrainingException = e
    //             println("Exception during training at epoch $epoch: ${e.message}")
    //             break
    //         }
    //     }
    // }



    private fun backpropagation(input: FloatArray, target: DoubleArray, m: Array<DoubleArray>, v: Array<DoubleArray>, mOutput: Array<DoubleArray>, vOutput: Array<DoubleArray>) {
        val (hidden, output) = forwardPassSimplified(input)
        val gradLossOutput = calculateOutputLayerGradient(output, target)
        updateWeightsAndBiasesForOutputLayer(hidden, gradLossOutput, mOutput, vOutput)

        val gradLossHidden = calculateHiddenLayerGradient(hidden, gradLossOutput)
        updateWeightsAndBiasesForHiddenLayer(input, hidden, gradLossHidden, m, v)
    }

    private fun calculateOutputLayerGradient(output: DoubleArray, target: DoubleArray): DoubleArray {
        return DoubleArray(outputSize) { i -> 2.0 * (output[i] - target[i]) }
    }

    private fun updateWeightsAndBiasesForOutputLayer(hidden: DoubleArray, gradLossOutput: DoubleArray, mOutput: Array<DoubleArray>, vOutput: Array<DoubleArray>) {
        val gradHiddenOutput = calculateGradHiddenOutput(hidden, gradLossOutput)
        adam(weightsHiddenOutput, gradHiddenOutput, mOutput, vOutput, 1, config.learningRate)
        updateBiases(biasOutput, gradLossOutput)
    }

    private fun calculateHiddenLayerGradient(hidden: DoubleArray, gradLossOutput: DoubleArray): DoubleArray {
        return DoubleArray(hiddenSize) { j ->
            if (hidden[j] <= 0) 0.0
            else gradLossOutput.indices.sumOf { k -> gradLossOutput[k] * weightsHiddenOutput[j][k] }
        }
    }

    private fun updateWeightsAndBiasesForHiddenLayer(input: FloatArray, hidden: DoubleArray, gradLossHidden: DoubleArray, m: Array<DoubleArray>, v: Array<DoubleArray>) {
        val gradInputHidden = calculateGradInputHidden(input, hidden, gradLossHidden)
        adam(weightsInputHidden, gradInputHidden, m, v, 1, config.learningRate)
        updateBiasesForHiddenLayer(hidden, gradLossHidden)
    }

    private fun calculateGradHiddenOutput(hidden: DoubleArray, gradLossOutput: DoubleArray): Array<DoubleArray> {
        return Array(hiddenSize) { j ->
            DoubleArray(outputSize) { i ->
                gradLossOutput[i] * hidden[j]
            }
        }
    }

    private fun calculateGradInputHidden(input: FloatArray, hidden: DoubleArray, gradLossHidden: DoubleArray): Array<DoubleArray> {
        return Array(inputSize) { i ->
            DoubleArray(hiddenSize) { j ->
                val gradRelu = if (hidden[j] > 0) 1.0 else 0.001
                gradLossHidden[j] * input[i] * gradRelu
            }
        }
    }

    private fun updateBiases(biases: DoubleArray, gradLoss: DoubleArray) {
        for (i in biases.indices) {
            biases[i] -= config.learningRate * gradLoss[i]
        }
    }

    private fun updateBiasesForHiddenLayer(hidden: DoubleArray, gradLossHidden: DoubleArray) {
        for (j in hidden.indices) {
            val gradRelu = if (hidden[j] > 0) 1.0 else 0.001
            biasHidden[j] -= config.learningRate * gradLossHidden[j] * gradRelu
        }
    }

    private fun adam(
        params: Array<DoubleArray>,
        grads: Array<DoubleArray>,
        m: Array<DoubleArray>,
        v: Array<DoubleArray>,
        t: Int,
        learningRate: Double = 0.001,
        beta1: Double = 0.9,
        beta2: Double = 0.999,
        epsilon: Double = 1e-8
    ) {
        for (i in params.indices) {
            for (j in params[i].indices) {
                m[i][j] = beta1 * m[i][j] + (1 - beta1) * grads[i][j]
                v[i][j] = beta2 * v[i][j] + (1 - beta2) * grads[i][j] * grads[i][j]
                val mHat = m[i][j] / (1 - beta1.pow(t))
                val vHat = v[i][j] / (1 - beta2.pow(t))
                params[i][j] -= learningRate * mHat / (sqrt(vHat) + epsilon)
            }
        }
    }

    // private fun trainWithAdam(
    //     inputs: List<FloatArray>,
    //     targets: List<DoubleArray>,
    //     validationRawInputs: List<FloatArray>,
    //     validationTargets: List<DoubleArray>,
    //     epochs: Int,
    //     batchSize: Int = 32,
    //     patience: Int = 10
    // ) {
    //     val preparedInputs = createNewFeature(inputs)
    //     val normalizedInputs = zScoreNormalization(preparedInputs)
    //     val preparedValidationInputs = createNewFeature(validationRawInputs)
    //     val normalizedValidationInputs = zScoreNormalization(preparedValidationInputs)
    //
    //     // Initialize Adam parameters
    //     val m = Array(weightsInputHidden.size) { DoubleArray(weightsInputHidden[0].size) { 0.0 } }
    //     val v = Array(weightsInputHidden.size) { DoubleArray(weightsInputHidden[0].size) { 0.0 } }
    //     val mOutput = Array(weightsHiddenOutput.size) { DoubleArray(weightsHiddenOutput[0].size) { 0.0 } }
    //     val vOutput = Array(weightsHiddenOutput.size) { DoubleArray(weightsHiddenOutput[0].size) { 0.0 } }
    //     var t = 0  // Initialize time step for Adam
    //
    //     var bestLoss = Double.MAX_VALUE
    //     var epochsWithoutImprovement = 0
    //
    //     for (epoch in 1..epochs) {
    //         var totalLoss = 0.0
    //         try {
    //             normalizedInputs.chunked(batchSize).zip(targets.chunked(batchSize)).forEach { (batchInputs, batchTargets) ->
    //                 batchInputs.zip(batchTargets).forEach { (input, target) ->
    //                     val (_, output) = forwardPass(input)
    //                     val gradOutputs = DoubleArray(outputSize) { i -> 2.0 * (output[i] - target[i]) }
    //                     val (gradInputHidden, gradHiddenOutput) = backpropagationWithAdam(input, gradOutputs)
    //
    //                     // Update weights with Adam optimizer for both layers
    //                     updateWeightsAdam(weightsInputHidden, gradInputHidden, m, v, ++t)
    //                     updateWeightsAdam(weightsHiddenOutput, gradHiddenOutput, mOutput, vOutput, t)
    //
    //                     totalLoss += mseLoss(output, target) + l2Regularization()
    //                 }
    //             }
    //
    //             val averageLoss = totalLoss / normalizedInputs.size
    //             trainingLossHistory.add(averageLoss)
    //
    //             val validationLoss = validate(normalizedValidationInputs, validationTargets)
    //             println("Epoch $epoch, Training Loss: $averageLoss, Validation Loss: $validationLoss")
    //
    //             if (validationLoss < bestLoss) {
    //                 bestLoss = validationLoss
    //                 epochsWithoutImprovement = 0
    //             } else {
    //                 epochsWithoutImprovement++
    //                 if (epochsWithoutImprovement >= patience) {
    //                     println("Early stopping triggered at epoch $epoch")
    //                     break
    //                 }
    //             }
    //
    //             config.learningRate *= 0.95  // Dynamically reduce the learning rate
    //         } catch (e: Exception) {
    //             lastTrainingException = e
    //             println("Exception during training at epoch $epoch: ${e.message}")
    //             break
    //         }
    //     }
    // }
    // private fun trainWithAdam(
    //     inputs: List<FloatArray>,
    //     targets: List<DoubleArray>,
    //     validationRawInputs: List<FloatArray>,
    //     validationTargets: List<DoubleArray>,
    //     epochs: Int,
    //     batchSize: Int = 32,
    //     patience: Int = 10
    // ) {
    //     if (inputs.isEmpty() || targets.isEmpty()) {
    //         println("Training data is empty. Aborting training.")
    //         return
    //     }
    //
    //     // Prétraitement
    //     println("Preparing inputs and targets for training...")
    //     val preparedInputs = createNewFeature(inputs)
    //     val normalizedInputs = zScoreNormalization(preparedInputs)
    //     val preparedValidationInputs = createNewFeature(validationRawInputs)
    //     val normalizedValidationInputs = zScoreNormalization(preparedValidationInputs)
    //
    //     println("Data sizes after preprocessing: ")
    //     println(" - Training data: ${normalizedInputs.size} samples.")
    //     println(" - Validation data: ${normalizedValidationInputs.size} samples.")
    //
    //     if (normalizedInputs.isEmpty() || normalizedValidationInputs.isEmpty()) {
    //         println("Preprocessing resulted in empty data. Aborting training.")
    //         return
    //     }
    //
    //     // Ajustement initial de la taille des batchs
    //     var adjustedBatchSize = minOf(batchSize, normalizedInputs.size)
    //     if (adjustedBatchSize <= 0) {
    //         println("Adjusted batch size is too small (${adjustedBatchSize}). Aborting training.")
    //         return
    //     }
    //     println("Using initial adjusted batch size: $adjustedBatchSize")
    //
    //     // Initialisation des paramètres Adam
    //     val m = Array(weightsInputHidden.size) { DoubleArray(weightsInputHidden[0].size) { 0.0 } }
    //     val v = Array(weightsInputHidden.size) { DoubleArray(weightsInputHidden[0].size) { 0.0 } }
    //     val mOutput = Array(weightsHiddenOutput.size) { DoubleArray(weightsHiddenOutput[0].size) { 0.0 } }
    //     val vOutput = Array(weightsHiddenOutput.size) { DoubleArray(weightsHiddenOutput[0].size) { 0.0 } }
    //     var t = 0  // Initialisation de l'étape de temps pour Adam
    //
    //     var bestLoss = Double.MAX_VALUE
    //     var epochsWithoutImprovement = 0
    //
    //     for (epoch in 1..epochs) {
    //         var totalLoss = 0.0
    //         try {
    //             println("Starting epoch $epoch...")
    //
    //             // Processus d'entraînement par batch
    //             val batches = normalizedInputs.chunked(adjustedBatchSize).zip(targets.chunked(adjustedBatchSize))
    //             if (batches.isEmpty()) {
    //                 println("No valid batches available. Aborting training at epoch $epoch.")
    //                 break
    //             }
    //
    //             batches.forEach { (batchInputs, batchTargets) ->
    //                 if (batchInputs.isEmpty() || batchTargets.isEmpty()) {
    //                     println("Skipping empty batch at epoch $epoch.")
    //                     return@forEach
    //                 }
    //
    //                 batchInputs.zip(batchTargets).forEach { (input, target) ->
    //                     val (_, output) = forwardPass(input)
    //
    //                     // Calcul des gradients et mise à jour des poids
    //                     val gradOutputs = DoubleArray(outputSize) { i -> 2.0 * (output[i] - target[i]) }
    //                     val (gradInputHidden, gradHiddenOutput) = backpropagationWithAdam(input, gradOutputs)
    //
    //                     updateWeightsAdam(weightsInputHidden, gradInputHidden, m, v, ++t)
    //                     updateWeightsAdam(weightsHiddenOutput, gradHiddenOutput, mOutput, vOutput, t)
    //
    //                     totalLoss += mseLoss(output, target) + l2Regularization()
    //                 }
    //             }
    //
    //             // Calcul de la perte moyenne et validation
    //             val averageLoss = totalLoss / normalizedInputs.size
    //             trainingLossHistory.add(averageLoss)
    //
    //             val validationLoss = validate(normalizedValidationInputs, validationTargets)
    //             println("Epoch $epoch complete. Training Loss: $averageLoss, Validation Loss: $validationLoss")
    //
    //             // Early stopping
    //             if (validationLoss < bestLoss) {
    //                 bestLoss = validationLoss
    //                 epochsWithoutImprovement = 0
    //             } else {
    //                 epochsWithoutImprovement++
    //                 if (epochsWithoutImprovement >= patience) {
    //                     println("Early stopping triggered at epoch $epoch due to no improvement.")
    //                     break
    //                 }
    //             }
    //
    //             // Ajustement dynamique du taux d'apprentissage
    //             config.learningRate *= 0.95
    //
    //             // Réévaluer la taille des batchs si nécessaire
    //             adjustedBatchSize = minOf(batchSize, normalizedInputs.size)
    //             if (adjustedBatchSize <= 0) {
    //                 println("Adjusted batch size too small after epoch $epoch. Aborting training.")
    //                 break
    //             }
    //
    //         } catch (e: Exception) {
    //             println("Exception during training at epoch $epoch: ${e.message}")
    //             e.printStackTrace()
    //             break
    //         }
    //     }
    // }
    private fun adamSimplified(
        weights: Array<DoubleArray>,
        grads: Array<DoubleArray>,
        m: Array<DoubleArray>,
        v: Array<DoubleArray>,
        t: Int,
        learningRate: Double = 0.001,
        beta1: Double = 0.9,
        beta2: Double = 0.999,
        epsilon: Double = 1e-8
    ) {
        for (i in weights.indices) {
            for (j in weights[i].indices) {
                m[i][j] = beta1 * m[i][j] + (1 - beta1) * grads[i][j]
                v[i][j] = beta2 * v[i][j] + (1 - beta2) * grads[i][j] * grads[i][j]

                val mHat = m[i][j] / (1 - beta1.pow(t))
                val vHat = v[i][j] / (1 - beta2.pow(t))

                weights[i][j] -= learningRate * mHat / (sqrt(vHat) + epsilon)
            }
        }
    }
    private fun forwardPassSimplified(input: FloatArray): Pair<DoubleArray, DoubleArray> {
        // Calcul d'une couche cachée simple
        val hidden = DoubleArray(hiddenSize) { i ->
            input.indices.sumOf { j -> input[j] * weightsInputHidden[j][i] } + biasHidden[i]
        }

        val activatedHidden = hidden.map { relu(it) }.toDoubleArray()

        // Calcul de la sortie
        val output = DoubleArray(outputSize) { i ->
            activatedHidden.indices.sumOf { j -> activatedHidden[j] * weightsHiddenOutput[j][i] } + biasOutput[i]
        }

        return Pair(activatedHidden, output)
    }
    fun trainSimplified(
        inputs: List<FloatArray>,
        targets: List<DoubleArray>,
        epochs: Int,
        batchSize: Int = 32
    ) {
        trainWithAdamSimplified(inputs, targets, epochs, batchSize)
    }
    private fun trainWithAdamSimplified(
        inputs: List<FloatArray>,
        targets: List<DoubleArray>,
        epochs: Int,
        batchSize: Int = 32
    ) {
        if (inputs.isEmpty() || targets.isEmpty()) {
            println("No training data available. Aborting.")
            return
        }

        // Normalisation basique
        val normalizedInputs = zScoreNormalization(inputs)
        if (normalizedInputs.isEmpty()) {
            println("Normalization resulted in empty data. Aborting.")
            return
        }

        // Initialisation des paramètres Adam
        val m = Array(weightsInputHidden.size) { DoubleArray(weightsInputHidden[0].size) { 0.0 } }
        val v = Array(weightsInputHidden.size) { DoubleArray(weightsInputHidden[0].size) { 0.0 } }
        var t = 0

        for (epoch in 1..epochs) {
            println("Starting epoch $epoch...")
            var totalLoss = 0.0

            try {
                // Processus par batch
                normalizedInputs.chunked(batchSize).zip(targets.chunked(batchSize)).forEach { (batchInputs, batchTargets) ->
                    batchInputs.zip(batchTargets).forEach { (input, target) ->
                        val (_, output) = forwardPassSimplified(input)
                        val gradOutputs = DoubleArray(outputSize) { i -> 2.0 * (output[i] - target[i]) }

                        // Calcul des gradients et mise à jour des poids
                        val (gradInputHidden, _) = backpropagationWithAdam(input, gradOutputs)
                        updateWeightsAdam(weightsInputHidden, gradInputHidden, m, v, ++t)

                        totalLoss += mseLoss(output, target)
                    }
                }

                val averageLoss = totalLoss / normalizedInputs.size
                println("Epoch $epoch complete. Training Loss: $averageLoss")
            } catch (e: Exception) {
                println("Exception during epoch $epoch: ${e.message}")
                e.printStackTrace()
                return
            }
        }
    }


    private fun adjustLearningRate(epoch: Int, validationLoss: Double) {
        if (validationLoss > bestLoss) {
            config.learningRate *= 0.9 // Réduis si la validation n’améliore pas
        }
    }

        private fun updateWeightsAdam(
            weights: Array<DoubleArray>,
            grads: Array<DoubleArray>,
            m: Array<DoubleArray>,
            v: Array<DoubleArray>, t: Int)
        {
            val beta1 = config.beta1
            val beta2 = config.beta2
            val epsilon = config.epsilon
            val weightDecay = config.weightDecay

            for (i in weights.indices) {
                for (j in weights[i].indices) {
                    m[i][j] = beta1 * m[i][j] + (1 - beta1) * grads[i][j]
                    v[i][j] = beta2 * v[i][j] + (1 - beta2) * grads[i][j] * grads[i][j]

                    val mHat = m[i][j] / (1 - beta1.pow(t.toDouble()))
                    val vHat = v[i][j] / (1 - beta2.pow(t.toDouble()))

                    //weights[i][j] -= config.learningRate * mHat / (sqrt(vHat) + epsilon)
                    weights[i][j] -= config.learningRate * mHat / (sqrt(vHat) + epsilon) + weightDecay * weights[i][j]
                }
            }
        }

        private fun backpropagationWithAdam(input: FloatArray, gradOutputs: DoubleArray): Pair<Array<DoubleArray>, Array<DoubleArray>> {
            val (hidden, _) = forwardPassSimplified(input)
            val gradInputHidden = Array(inputSize) { DoubleArray(hiddenSize) { 0.0 } }
            val gradHiddenOutput = Array(hiddenSize) { DoubleArray(outputSize) { 0.0 } }

            // Calculate gradients for weights between the hidden layer and the output layer
            for (i in 0 until outputSize) {
                for (j in 0 until hiddenSize) {
                    gradHiddenOutput[j][i] = gradOutputs[i] * hidden[j]
                }
            }

            // Calculate gradients for weights between the input layer and the hidden layer
            for (i in 0 until inputSize) {
                for (j in 0 until hiddenSize) {
                    var gradLossHidden = 0.0
                    for (k in 0 until outputSize) {
                        gradLossHidden += gradOutputs[k] * weightsHiddenOutput[j][k]
                    }
                    // Correctly apply the ReLU derivative
                    val reluDerivative = if (hidden[j] > 0) 1.0 else 0.001
                    gradInputHidden[i][j] = gradLossHidden * reluDerivative * input[i]
                }
            }

            // Return both sets of gradients
            return Pair(gradInputHidden, gradHiddenOutput)
        }

    private fun validate(inputs: List<FloatArray>, targets: List<DoubleArray>): Double {
            var totalLoss = 0.0
            for ((input, target) in inputs.zip(targets)) {
                val output = forwardPassSimplified(input).second
                totalLoss += mseLoss(output, target) + l2Regularization()
            }
            return totalLoss / inputs.size
        }

    }

