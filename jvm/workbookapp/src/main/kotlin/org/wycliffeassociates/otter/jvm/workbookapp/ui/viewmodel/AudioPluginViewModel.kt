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

import io.reactivex.Completable
import io.reactivex.Maybe
import io.reactivex.Single
import javafx.beans.property.SimpleObjectProperty
import javafx.beans.property.SimpleStringProperty
import org.wycliffeassociates.otter.common.data.workbook.AssociatedAudio
import org.wycliffeassociates.otter.common.data.workbook.Take
import org.wycliffeassociates.otter.common.domain.content.FileNamer
import org.wycliffeassociates.otter.common.domain.content.Recordable
import org.wycliffeassociates.otter.common.domain.content.TakeActions
import org.wycliffeassociates.otter.common.domain.content.WorkbookFileNamerBuilder
import org.wycliffeassociates.otter.common.domain.plugins.AudioPluginData
import org.wycliffeassociates.otter.common.domain.plugins.IAudioPlugin
import org.wycliffeassociates.otter.common.domain.plugins.LaunchPlugin
import org.wycliffeassociates.otter.common.domain.plugins.PluginParameters
import org.wycliffeassociates.otter.common.persistence.repositories.IAudioPluginRepository
import org.wycliffeassociates.otter.common.persistence.repositories.PluginType
import org.wycliffeassociates.otter.jvm.workbookapp.di.IDependencyGraphProvider
import org.wycliffeassociates.otter.jvm.workbookapp.ui.screens.dialogs.AddPluginDialog
import tornadofx.*
import java.io.File
import javax.inject.Inject

class AudioPluginViewModel : ViewModel() {
    @Inject lateinit var pluginRepository: IAudioPluginRepository
    @Inject lateinit var launchPlugin: LaunchPlugin
    @Inject lateinit var takeActions: TakeActions

    private val workbookDataStore: WorkbookDataStore by inject()

    val pluginNameProperty = SimpleStringProperty()
    val selectedRecorderProperty = SimpleObjectProperty<AudioPluginData>()
    val selectedEditorProperty = SimpleObjectProperty<AudioPluginData>()
    val selectedMarkerProperty = SimpleObjectProperty<AudioPluginData>()

    init {
        (app as IDependencyGraphProvider).dependencyGraph.inject(this)
    }

    fun getPlugin(pluginType: PluginType): Maybe<IAudioPlugin> {
        return pluginRepository.getPlugin(pluginType)
    }

    fun record(recordable: Recordable): Single<TakeActions.Result> {
        val params = constructPluginParameters()
        return takeActions.record(
            audio = recordable.audio,
            projectAudioDir = workbookDataStore.activeProjectFilesAccessor.audioDir,
            namer = createFileNamer(recordable),
            pluginParameters = params
        )
    }

    fun import(recordable: Recordable, take: File): Completable {
        return takeActions.import(
            audio = recordable.audio,
            projectAudioDir = workbookDataStore.activeProjectFilesAccessor.audioDir,
            namer = createFileNamer(recordable),
            take = take
        )
    }

    private fun constructPluginParameters(action: String = ""): PluginParameters {
        val workbook = workbookDataStore.workbook
        val sourceAudio = workbookDataStore.getSourceAudio()
        val sourceText = workbookDataStore.getSourceText().blockingGet()

        val chapterLabel = messages[workbookDataStore.activeChapterProperty.value.label]
        val chapterNumber = workbookDataStore.activeChapterProperty.value.sort
        val verseTotal = workbookDataStore.activeChapterProperty.value.chunks.blockingLast().end
        val chunkLabel = workbookDataStore.activeChunkProperty.value?.let {
            messages[workbookDataStore.activeChunkProperty.value.label]
        }
        val chunkNumber = workbookDataStore.activeChunkProperty.value?.sort
        val resourceLabel = workbookDataStore.activeResourceComponentProperty.value?.let {
            messages[workbookDataStore.activeResourceComponentProperty.value.label]
        }

        return PluginParameters(
            languageName = workbook.target.language.name,
            bookTitle = workbook.target.title,
            chapterLabel = chapterLabel,
            chapterNumber = chapterNumber,
            verseTotal = verseTotal,
            chunkLabel = chunkLabel,
            chunkNumber = chunkNumber,
            resourceLabel = resourceLabel,
            sourceChapterAudio = sourceAudio?.file,
            sourceChunkStart = sourceAudio?.start,
            sourceChunkEnd = sourceAudio?.end,
            sourceText = sourceText,
            actionText = action
        )
    }

    private fun createFileNamer(recordable: Recordable): FileNamer {
        return WorkbookFileNamerBuilder.createFileNamer(
            workbook = workbookDataStore.workbook,
            chapter = workbookDataStore.chapter,
            chunk = workbookDataStore.chunk,
            recordable = recordable,
            rcSlug = workbookDataStore.activeResourceMetadata.identifier
        )
    }

    fun edit(audio: AssociatedAudio, take: Take): Single<TakeActions.Result> {
        val params = constructPluginParameters()
        return takeActions.edit(audio, take, params)
    }

    fun mark(audio: AssociatedAudio, take: Take): Single<TakeActions.Result> {
        val params = constructPluginParameters(messages["markAction"])
        return takeActions.mark(audio, take, params)
    }

    fun addPlugin(record: Boolean, edit: Boolean) {
        find<AddPluginDialog>().open()
        find<AddPluginViewModel>().apply {
            canRecordProperty.value = record
            canEditProperty.value = edit
        }
    }
}
