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
import io.reactivex.Observable
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.PublishSubject
import javafx.application.Platform
import javafx.beans.binding.Bindings
import javafx.beans.binding.StringBinding
import javafx.beans.property.SimpleBooleanProperty
import javafx.beans.property.SimpleObjectProperty
import javafx.beans.property.SimpleStringProperty
import javafx.collections.FXCollections
import javafx.collections.ObservableList
import javafx.scene.control.ButtonType
import org.slf4j.LoggerFactory
import org.wycliffeassociates.otter.common.data.workbook.Chunk
import org.wycliffeassociates.otter.common.data.workbook.DateHolder
import org.wycliffeassociates.otter.common.data.workbook.Take
import org.wycliffeassociates.otter.common.device.IAudioPlayer
import org.wycliffeassociates.otter.common.domain.content.Recordable
import org.wycliffeassociates.otter.common.domain.content.TakeActions
import org.wycliffeassociates.otter.common.persistence.repositories.PluginType
import org.wycliffeassociates.otter.jvm.controls.ListAnimationMediator
import org.wycliffeassociates.otter.jvm.controls.card.ScriptureTakeCard
import org.wycliffeassociates.otter.jvm.controls.card.events.DeleteTakeEvent
import org.wycliffeassociates.otter.jvm.controls.card.events.TakeEvent
import org.wycliffeassociates.otter.jvm.utils.onChangeAndDoNow
import org.wycliffeassociates.otter.jvm.workbookapp.plugin.PluginClosedEvent
import org.wycliffeassociates.otter.jvm.workbookapp.plugin.PluginOpenedEvent
import org.wycliffeassociates.otter.jvm.workbookapp.ui.OtterApp
import org.wycliffeassociates.otter.jvm.workbookapp.ui.model.TakeCardModel
import tornadofx.*
import java.io.File
import java.text.MessageFormat
import java.text.SimpleDateFormat
import java.util.*
import io.reactivex.rxkotlin.toObservable as toRxObservable

class RecordScriptureViewModel : ViewModel() {

    private val logger = LoggerFactory.getLogger(RecordScriptureViewModel::class.java)

    private enum class StepDirection {
        FORWARD,
        BACKWARD
    }

    private val workbookDataStore: WorkbookDataStore by inject()
    private val audioPluginViewModel: AudioPluginViewModel by inject()

    // This will be bidirectionally bound to workbookViewModel's activeChunkProperty
    private val activeChunkProperty = SimpleObjectProperty<Chunk>()
    private val activeChunk: Chunk
        get() = activeChunkProperty.value ?: throw IllegalStateException("Chunk is null")

    private val titleProperty = SimpleStringProperty()
    private var title by titleProperty

    private val chunkList: ObservableList<Chunk> = observableListOf()
    val hasNext = SimpleBooleanProperty(false)
    val hasPrevious = SimpleBooleanProperty(false)

    private var activeChunkSubscription: Disposable? = null

    val recordableProperty = SimpleObjectProperty<Recordable?>()
    var recordable by recordableProperty

    val currentTakeNumberProperty = SimpleObjectProperty<Int?>()
    val contextProperty = SimpleObjectProperty(PluginType.RECORDER)
    val sourceAudioAvailableProperty = workbookDataStore.sourceAudioAvailableProperty
    val sourceAudioPlayerProperty = SimpleObjectProperty<IAudioPlayer?>(null)

    val snackBarObservable: PublishSubject<String> = PublishSubject.create()
    val takeCardModels: ObservableList<TakeCardModel> = FXCollections.observableArrayList()
    val takeCardViews: ObservableList<ScriptureTakeCard> = FXCollections.observableArrayList()

    val showImportProgressDialogProperty = SimpleBooleanProperty(false)
    val showImportSuccessDialogProperty = SimpleBooleanProperty(false)
    val showImportFailDialogProperty = SimpleBooleanProperty(false)

    private val disposables = CompositeDisposable()

    val breadcrumbTitleBinding = currentTakeNumberProperty.stringBinding {
        it?.let { take ->
            MessageFormat.format(
                messages["takeTitle"],
                messages["take"],
                take
            )
        } ?: messages["take"]
    }

    init {
        activeChunkProperty.bindBidirectional(workbookDataStore.activeChunkProperty)

        workbookDataStore.activeChapterProperty.onChangeAndDoNow { chapter ->
            chapter?.let {
                getChunkList(chapter.chunks)
                if (activeChunkProperty.value == null) {
                    recordable = it
                    setHasNextAndPrevious()
                }
            }
        }

        activeChunkProperty.onChangeAndDoNow { chunk ->
            setHasNextAndPrevious()
            if (chunk != null) {
                setTitle(chunk)
                // This will trigger loading takes
                recordable = chunk
            } else {
                workbookDataStore.activeChapterProperty.value?.let {
                    recordable = it
                }
            }
        }

        recordableProperty.onChangeAndDoNow {
            clearDisposables()
            subscribeSelectedTakePropertyToRelay()
            loadTakes()
        }

        workbookDataStore.sourceAudioProperty.onChangeAndDoNow {
            openSourceAudioPlayer()
        }

        audioPluginViewModel.pluginNameProperty.bind(pluginNameBinding())

        takeCardModels.onChange {
            val animationMediator = ListAnimationMediator<ScriptureTakeCard>()
            takeCardViews.setAll(
                it.list.map { takeCardModel ->
                    ScriptureTakeCard().apply {
                        animationMediatorProperty.set(animationMediator)
                        takeProperty.set(takeCardModel.take)
                        audioPlayerProperty.set(takeCardModel.audioPlayer)
                        selectedProperty.set(takeCardModel.selected)
                        lastModifiedProperty.set(
                            SimpleDateFormat.getDateTimeInstance(
                                SimpleDateFormat.SHORT,
                                SimpleDateFormat.SHORT,
                                Locale.getDefault()
                            ).format(takeCardModel.take.file.lastModified())
                        )
                        takeLabelProperty.set(
                            MessageFormat.format(
                                FX.messages["takeTitle"],
                                FX.messages["take"],
                                takeCardModel.take.number
                            )
                        )
                        setOnTakeDelete {
                            error(
                                messages["deleteTakePrompt"],
                                messages["cannotBeUndone"],
                                ButtonType.YES,
                                ButtonType.NO,
                                title = messages["deleteTakePrompt"]
                            ) { button: ButtonType ->
                                if (button == ButtonType.YES) {
                                    fireEvent(DeleteTakeEvent(takeCardModel.take))
                                }
                            }
                        }
                        setOnTakeEdit {
                            fireEvent(
                                TakeEvent(takeCardModel.take, {}, TakeEvent.EDIT_TAKE)
                            )
                        }
                        setOnTakeSelected {
                            fireEvent(
                                TakeEvent(takeCardModel.take, {}, TakeEvent.SELECT_TAKE)
                            )
                        }
                    }
                }
            )
        }
    }

    fun nextChunk() {
        closePlayers()
        stepToChunk(StepDirection.FORWARD)
    }

    fun previousChunk() {
        closePlayers()
        stepToChunk(StepDirection.BACKWARD)
    }

    private fun setHasNextAndPrevious() {
        activeChunkProperty.value?.let { chunk ->
            if (chunkList.isNotEmpty()) {
                hasNext.set(chunk.start < chunkList.last().start)
                hasPrevious.set(chunk.start > chunkList.first().start)
            } else {
                hasNext.set(false)
                hasPrevious.set(false)
                chunkList.sizeProperty.onChangeOnce {
                    setHasNextAndPrevious()
                }
            }
        } ?: run {
            hasNext.set(false)
            hasPrevious.set(false)
        }
    }

    private fun setTitle(chunk: Chunk) {
        val label = messages["verse"]
        val start = chunk.start
        title = "$label $start"
    }

    private fun getChunkList(chunks: Observable<Chunk>) {
        activeChunkSubscription?.dispose()
        activeChunkSubscription = chunks
            .toList()
            .map { it.sortedBy { chunk -> chunk.start } }
            .observeOnFx()
            .doOnError { e ->
                logger.error("Error in getting the chunk list", e)
            }
            .subscribe { list ->
                chunkList.setAll(list)
            }
    }

    private fun stepToChunk(direction: StepDirection) {
        val amount = when (direction) {
            StepDirection.FORWARD -> 1
            StepDirection.BACKWARD -> -1
        }
        chunkList
            .find { it.start == activeChunk.start + amount }
            ?.let { newChunk -> activeChunkProperty.set(newChunk) }
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
                            loadTakes()
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
                    TakeActions.Result.SUCCESS -> {
                        takeEvent.onComplete()
                        loadTakes()
                    }
                }
            }
    }

    fun selectTake(take: Take) {
        recordable?.audio?.selectTake(take) ?: throw IllegalStateException("Recordable is null")
        workbookDataStore.updateSelectedTakesFile()
        take.file.setLastModified(System.currentTimeMillis())
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
                    {
                        showImportSuccessDialogProperty.set(true)
                        loadTakes()
                    },
                    {
                        showImportFailDialogProperty.set(true)
                    }
                )
        }
    }

    fun deleteTake(take: Take) {
        stopPlayers()
        take.deletedTimestamp.accept(DateHolder.now())
        removeOnDeleted(take)
    }

    fun dialogTitleBinding(): StringBinding {
        return Bindings.createStringBinding(
            {
                String.format(
                    messages["sourceDialogTitle"],
                    currentTakeNumberProperty.value,
                    audioPluginViewModel.pluginNameProperty.value
                )
            },
            audioPluginViewModel.pluginNameProperty,
            currentTakeNumberProperty
        )
    }

    fun dialogTextBinding(): StringBinding {
        return Bindings.createStringBinding(
            {
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
            {
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

    fun loadTakes() {
        recordable?.audio?.let { audio ->
            // selectedTakeProperty may not have been updated yet so ask for the current selected take
            val selected = audio.selected.value?.value

            val takes = audio.getAllTakes()
                .filter { it.isNotDeleted() }
                .map { take ->
                    take.mapToCardModel(take == selected)
                }
                .sortedWith(
                    compareByDescending<TakeCardModel> { it.selected }
                        .thenByDescending { it.take.file.lastModified() }
                )

            takeCardModels.setAll(takes)
        }
    }

    private fun removeOnDeleted(take: Take) {
        take.deletedTimestamp
            .filter { dateHolder -> dateHolder.value != null }
            .doOnError { e ->
                logger.error("Error in removing deleted take: $take", e)
            }
            .subscribe {
                removeFromTakes(take)
            }
            .let { disposables.add(it) }
    }

    private fun removeFromTakes(take: Take) {
        Platform.runLater {
            takeCardModels.removeAll { it.take == take }
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
        sourceAudioPlayerProperty.value?.stop()
    }

    private fun subscribeSelectedTakePropertyToRelay() {
        recordable?.audio?.let { audio ->
            audio
                .selected
                .doOnError { e ->
                    logger.error("Error in subscribing take to relay for audio: $audio", e)
                }
                .observeOnFx()
                .subscribe {
                    loadTakes()
                }
                .let { disposables.add(it) }
        }
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
