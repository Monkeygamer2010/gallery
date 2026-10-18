/*
 * Copyright 2026 The PhoneAgent Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.phoneagent.os

import android.content.Context
import android.content.Intent

/**
 * Non-exported alias of MainActivity (AndroidManifest.xml). The main activity is exported because
 * it is the launcher, so any app can send it an explicit intent; only intents sent to this alias
 * (which other apps cannot reach) may carry a `com.google.ai.edge.gallery://` link.
 */
const val INTERNAL_LINKS_ALIAS = "com.google.ai.edge.gallery.phoneagent.InternalLinks"

/** Points [intent] at the internal-links alias. */
fun internalLink(context: Context, intent: Intent): Intent =
  intent.setClassName(context, INTERNAL_LINKS_ALIAS)

/** True when the intent came in through the internal-links alias. */
val Intent.isInternalLink: Boolean
  get() = component?.className == INTERNAL_LINKS_ALIAS
