/**
 * Copyright (C) 2020, 2021 Wycliffe Associates
 *
 * This file is part of Orature.
 *
 * Orature is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Orature is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Orature.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.wycliffeassociates.otter.jvm.workbookapp.ui.viewmodel

import com.github.thomasnield.rxkotlinfx.observeOnFx
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.schedulers.Schedulers
import io.reactivex.rxkotlin.toObservable as toRxObservable
import io.reactivex.subjects.PublishSubject
import javafx.application.Platform
import javafx.beans.binding.Bindings
import javafx.beans.binding.StringBinding
import javafx.beans.property.SimpleBooleanProperty
import javafx.beans.property.SimpleObjectProperty
import javafx.collections.FXCollections
import javafx.collections.ObservableList
import org.slf4j.LoggerFactory
import org.wycliffeassociates.otter.common.data.workbook.AssociatedAudio
import org.wycliffeassociates.otter.common.data.workbook.DateHolder
import org.wycliffeassociates.otter.common.data.workbook.Take
import org.wycliffeassociates.otter.common.device.IAudioPlayer
import org.wycliffeassociates.otter.common.domain.content.Recordable
import org.wycliffeassociates.otter.common.domain.content.TakeActions
import org.wycliffeassociates.otter.common.persistence.repositories.PluginType
import org.wycliffeassociates.otter.jvm.controls.card.events.TakeEvent
import org.wycliffeassociates.otter.jvm.utils.onChangeAndDoNow
import org.wycliffeassociates.otter.jvm.workbookapp.plugin.PluginClosedEvent
import org.wycliffeassociates.otter.jvm.workbookapp.plugin.PluginOpenedEvent
import org.wycliffeassociates.otter.jvm.workbookapp.ui.OtterApp
import org.wycliffeassociates.otter.jvm.workbookapp.ui.model.TakeCardModel
import tornadofx.*
import java.io.File
import java.util.concurrent.Callable

open class RecordableViewModel(
    private val audioPluginViewModel: AudioPluginViewModel
) : ViewModel() {

    private val logger = LoggerFactory.getLogger(RecordableViewModel::class.java)

    val workbookDataStore: WorkbookDataStore by inject()

    val recordableProperty = SimpleObjectProperty<Recordable?>()
    var recordable by recordableProperty

    val currentTakeNumberProperty = SimpleObjectProperty<Int?>()

    val contextProperty = SimpleObjectProperty(PluginType.RECORDER)

    val snackBarObservable: PublishSubject<String> = PublishSubject.create()

    val takeCardModels: ObservableList<TakeCardModel> = FXCollections.observableArrayList()
    val selectedTakeProperty = SimpleObjectProperty<TakeCardModel?>()

    val sourceAudioAvailableProperty = workbookDataStore.sourceAudioAvailableProperty
    val sourceAudioPlayerProperty = SimpleObjectProperty<IAudioPlayer?>(null)

    val showImportProgressDialogProperty = SimpleBooleanProperty(false)
    val showImportSuccessDialogProperty = SimpleBooleanProperty(false)
    val showImportFailDialogProperty = SimpleBooleanProperty(false)

    private val disposables = CompositeDisposable()

    init {
        selectedTakeProperty.onChangeAndDoNow {
            sortTakes()
        }

        recordableProperty.onChange {
            clearDisposables()
            it?.audio?.let { audio ->
                subscribeSelectedTakePropertyToRelay(audio)
                loadTakes(audio)
            }
        }

        workbookDataStore.sourceAudioProperty.onChangeAndDoNow {
            openSourceAudioPlayer()
        }

        audioPluginViewModel.pluginNameProperty.bind(pluginNameBinding())
    }

    fun recordNewTake() {
        closePlayers()
        recordable?.let { rec ->
            contextProperty.set(PluginType.RECORDER)
            rec.audio.getNewTakeNumber()
                .flatMapMaybe { takeNumber ->
                    currentTakeNumberProperty.set(takeNumber)
                    audioPluginViewModel.getPlugin(PluginType.RECORDER)
                }
                .flatMapSingle { plugin ->
                    fire(PluginOpenedEvent(PluginType.RECORDER, plugin.isNativePlugin()))
                    audioPluginViewModel.record(rec)
                }
                .observeOnFx()
                .doOnError { e ->
                    logger.error("Error in recording a new take", e)
                }
                .onErrorReturn { TakeActions.Result.NO_PLUGIN }
                .subscribe { result: TakeActions.Result ->
                    fire(PluginClosedEvent(PluginType.RECORDER))
                    when (result) {
                        TakeActions.Result.NO_PLUGIN -> snackBarObservable.onNext(messages["noRecorder"])
                        TakeActions.Result.SUCCESS, TakeActions.Result.NO_AUDIO -> {
                        }
                    }
                }
        } ?: throw IllegalStateException("Recordable is null")
    }

    fun processTakeWithPlugin(takeEvent: TakeEvent, pluginType: PluginType) {
        closePlayers()
        contextProperty.set(pluginType)
        currentTakeNumberProperty.set(takeEvent.take.number)
        audioPluginViewModel
            .getPlugin(pluginType)
            .flatMapSingle { plugin ->
                fire(PluginOpenedEvent(pluginType, plugin.isNativePlugin()))
                when (pluginType) {
                    PluginType.EDITOR -> audioPluginViewModel.edit(recordable!!.audio, takeEvent.take)
                    PluginType.MARKER -> audioPluginViewModel.mark(recordable!!.audio, takeEvent.take)
                    else -> null
                }
            }
            .observeOnFx()
            .doOnError { e ->
                logger.error("Error in processing take with plugin type: $pluginType", e)
            }
            .onErrorReturn { TakeActions.Result.NO_PLUGIN }
            .subscribe { result: TakeActions.Result ->
                currentTakeNumberProperty.set(null)
                fire(PluginClosedEvent(pluginType))
                when (result) {
                    TakeActions.Result.NO_PLUGIN -> snackBarObservable.onNext(messages["noEditor"])
                    TakeActions.Result.SUCCESS -> takeEvent.onComplete()
                }
            }
    }

    fun selectTake(take: Take) {
        clearSelectedTake()
        setSelectedTake(take)
    }

    fun selectTake(filename: String) {
        val take = takeCardModels.find { it.take.name == filename }
        take?.let {
            selectTake(it.take)
        } ?: clearSelectedTake()
    }

    fun importTakes(files: List<File>) {
        showImportProgressDialogProperty.set(true)
        closePlayers()

        recordable?.let { rec ->
            files.toRxObservable()
                .subscribeOn(Schedulers.io())
                .flatMapCompletable { takeFile ->
                    audioPluginViewModel.import(rec, takeFile)
                }
                .observeOnFx()
                .doOnError { e ->
                    logger.error("Error in importing take", e)
                }
                .doFinally {
                    showImportProgressDialogProperty.set(false)
                }
                .subscribe(
                    { showImportSuccessDialogProperty.set(true) },
                    { showImportFailDialogProperty.set(true) }
                )
        }
    }

    private fun clearSelectedTake() {
        selectedTakeProperty.value?.let { selectedTake ->
            selectedTake.selected = false
            addToAlternateTakes(selectedTake)
        }
        selectedTakeProperty.set(null)
    }

    private fun setSelectedTake(take: Take) {
        val found = takeCardModels.find {
            take.equals(it.take)
        }
        found?.let { takeModel ->
            removeFromAlternateTakes(take)
            takeModel.selected = true
            recordable?.audio?.selectTake(takeModel.take) ?: throw IllegalStateException("Recordable is null")
            selectedTakeProperty.set(takeModel)
            workbookDataStore.updateSelectedTakesFile()
            take.file.setLastModified(System.currentTimeMillis())
        }
    }

    fun deleteTake(take: Take) {
        stopPlayers()
        take.deletedTimestamp.accept(DateHolder.now())
    }

    fun dialogTitleBinding(): StringBinding {
        return Bindings.createStringBinding(
            Callable {
                String.format(
                    messages["sourceDialogTitle"],
                    currentTakeNumberProperty.get(),
                    audioPluginViewModel.pluginNameProperty.get()
                )
            },
            audioPluginViewModel.pluginNameProperty,
            currentTakeNumberProperty
        )
    }

    fun dialogTextBinding(): StringBinding {
        return Bindings.createStringBinding(
            Callable {
                String.format(
                    messages["sourceDialogMessage"],
                    currentTakeNumberProperty.get(),
                    audioPluginViewModel.pluginNameProperty.get(),
                    audioPluginViewModel.pluginNameProperty.get()
                )
            },
            audioPluginViewModel.pluginNameProperty,
            currentTakeNumberProperty
        )
    }

    fun pluginNameBinding(): StringBinding {
        return Bindings.createStringBinding(
            Callable {
                when (contextProperty.get()) {
                    PluginType.RECORDER -> {
                        audioPluginViewModel.selectedRecorderProperty.get()?.name
                    }
                    PluginType.EDITOR -> {
                        audioPluginViewModel.selectedEditorProperty.get()?.name
                    }
                    PluginType.MARKER -> {
                        audioPluginViewModel.selectedMarkerProperty.get()?.name
                    }
                    null -> throw IllegalStateException("Action is not supported!")
                }
            },
            contextProperty,
            audioPluginViewModel.selectedRecorderProperty,
            audioPluginViewModel.selectedEditorProperty,
            audioPluginViewModel.selectedMarkerProperty
        )
    }

    @Suppress("ProtectedInFinal", "Unused")
    protected fun finalize() {
        clearDisposables()
    }

    private fun clearDisposables() {
        disposables.clear()
    }

    private fun Take.isNotDeleted() = deletedTimestamp.value?.value == null

    private fun loadTakes(audio: AssociatedAudio) {
        // selectedTakeProperty may not have been updated yet so ask for the current selected take
        val selected = audio.selected.value?.value

        val takes =
            audio.getAllTakes()
                .filter { it.isNotDeleted() }
                .map { take ->
                    take.mapToCardModel(take.equals(selected))
                }

        val selectedModel = takes.find { it.selected }
        selectedTakeProperty.set(selectedModel)

        takeCardModels.clear()
        takeCardModels.addAll(takes)
        sortTakes()

        audio.takes
            .filter { it.isNotDeleted() }
            .doOnError { e ->
                logger.error("Error in loading audio takes for audio: $audio", e)
            }
            .subscribe { take ->
                if (takeCardModels.find { it.take.equals(take) } == null) {
                    addToAlternateTakes(
                        take.mapToCardModel(take.equals(selected))
                    )
                }
                removeOnDeleted(take)
            }
            .let { disposables.add(it) }
    }

    private fun removeOnDeleted(take: Take) {
        take.deletedTimestamp
            .filter { dateHolder -> dateHolder.value != null }
            .doOnError { e ->
                logger.error("Error in removing deleted take: $take", e)
            }
            .subscribe {
                removeFromAlternateTakes(take)
            }
            .let { disposables.add(it) }
    }

    private fun subscribeSelectedTakePropertyToRelay(audio: AssociatedAudio) {
        audio
            .selected
            .doOnError { e ->
                logger.error("Error in subscribing take to relay for audio: $audio", e)
            }
            .observeOnFx()
            .subscribe { takeHolder ->
                takeCardModels.forEach { it.selected = false }
                val takeModel = takeCardModels.find { it.take == takeHolder.value }
                takeModel?.selected = true
                selectedTakeProperty.set(takeModel)
            }
            .let { disposables.add(it) }
    }

    private fun sortTakes() {
        FXCollections.sort(
            takeCardModels
        ) { take1, take2 ->
            when {
                take1.selected == take2.selected -> take1.take.number.compareTo(take2.take.number)
                take1.selected -> 1
                else -> -1
            }
        }
    }

    private fun addToAlternateTakes(take: TakeCardModel) {
        Platform.runLater {
            if (!takeCardModels.contains(take)) {
                takeCardModels.add(take)
                sortTakes()
            }
        }
    }

    private fun removeFromAlternateTakes(take: Take) {
        Platform.runLater {
            takeCardModels.removeAll { it.take.equals(take) }
        }
    }

    fun openPlayers() {
        takeCardModels.forEach { it.audioPlayer.load(it.take.file) }
        openSourceAudioPlayer()
    }

    fun openSourceAudioPlayer() {
        workbookDataStore.sourceAudioProperty.value?.let { source ->
            val audioPlayer = (app as OtterApp).dependencyGraph.injectPlayer()
            audioPlayer.loadSection(source.file, source.start, source.end)
            sourceAudioPlayerProperty.set(audioPlayer)
        }
    }

    fun closePlayers() {
        takeCardModels.forEach { it.audioPlayer.close() }
        sourceAudioPlayerProperty.value?.close()
    }

    fun stopPlayers() {
        takeCardModels.forEach { it.audioPlayer.stop() }
        selectedTakeProperty.value?.audioPlayer?.stop()
        sourceAudioPlayerProperty.value?.stop()
    }

    fun Take.mapToCardModel(selected: Boolean): TakeCardModel {
        val ap: IAudioPlayer = (app as OtterApp).dependencyGraph.injectPlayer()
        ap.load(this.file)
        return TakeCardModel(
            this,
            selected,
            ap,
            FX.messages["edit"].capitalize(),
            FX.messages["delete"].capitalize(),
            FX.messages["marker"].capitalize(),
            FX.messages["play"].capitalize(),
            FX.messages["pause"].capitalize()
        )
    }
}
