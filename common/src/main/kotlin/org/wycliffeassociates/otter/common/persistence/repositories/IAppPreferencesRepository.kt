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
package org.wycliffeassociates.otter.common.persistence.repositories

import io.reactivex.Completable
import io.reactivex.Maybe
import io.reactivex.Single
import org.wycliffeassociates.otter.common.data.primitives.Language

interface IAppPreferencesRepository {
    fun resumeProjectId(): Single<Int>
    fun setResumeProjectId(id: Int): Completable
    fun lastResource(): Single<String>
    fun setLastResource(resource: String): Completable
    fun getOutputDevice(): Maybe<String>
    fun setOutputDevice(mixer: String): Completable
    fun getInputDevice(): Maybe<String>
    fun setInputDevice(mixer: String): Completable
    fun localeLanguage(): Maybe<Language>
    fun setLocaleLanguage(language: Language): Completable
}
