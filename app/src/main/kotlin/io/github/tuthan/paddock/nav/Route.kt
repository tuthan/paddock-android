package io.github.tuthan.paddock.nav

/** The screens the router draws. Home, Spaces and Activity share the bottom bar; the rest open from them (see [NavState.backTo] for where each returns). */
internal enum class Route { Welcome, Home, Output, Compose, Snippets, Activity, Spaces, Settings, AddMachine, AlertRelay, Decision, GuardedAnswers, Machines, Machine }
