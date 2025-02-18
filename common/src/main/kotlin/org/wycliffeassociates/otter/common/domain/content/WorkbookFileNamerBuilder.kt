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
package org.wycliffeassociates.otter.common.domain.content

import org.wycliffeassociates.otter.common.data.workbook.*
import org.wycliffeassociates.otter.common.domain.content.FileNamer.Companion.DEFAULT_RC_SLUG

object WorkbookFileNamerBuilder {
    fun createFileNamer(
        workbook: Workbook,
        chapter: Chapter,
        chunk: Chunk?,
        recordable: Recordable,
        rcSlug: String
    ) = FileNamer(
        bookSlug = workbook.target.slug,
        languageSlug = workbook.target.language.slug,
        chapterCount = workbook.target.chapters.count().blockingGet(),
        chapterTitle = chapter.title,
        chapterSort = chapter.sort,
        chunkCount = chapter.chunks.count().blockingGet(),
        start = chunk?.start,
        end = chunk?.end,
        contentType = recordable.contentType,
        sort = recordable.sort,
        rcSlug = if (workbook.source.language.slug == workbook.target.language.slug) {
            rcSlug
        } else {
            DEFAULT_RC_SLUG
        }
    )
}