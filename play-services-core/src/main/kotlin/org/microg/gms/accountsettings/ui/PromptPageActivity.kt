/*
 * SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.accountsettings.ui

/**
 * Google's confirmation page for a sign-in prompt (useoxygen/cyclon#842): the account page in its own task, so all
 * prompt pages share one task and a newer prompt's page replaces the older one (the start clears the task). It is
 * not shown over the lock screen.
 */
class PromptPageActivity : MainActivity()
