/*
 * Copyright 2026 Geert Bevin (gbevin[remove] at uwyn dot com)
 * Licensed under the Apache License, Version 2.0 (the "License")
 */
package rife.bld.extension.nativeapp;

import rife.bld.WebProject;
import rife.bld.extension.NativeAppProject;
import rife.bld.operations.AbstractOperation;

/**
 * Finds the simulator for a target, the one that the settings name or else
 * the first one with the newest system of the target, prints its UDID, and
 * starts it when that's asked for, for a script that installs and launches
 * an app in it.
 *
 * @author Geert Bevin (gbevin[remove] at uwyn dot com)
 * @since 1.11.0
 */
public class SimulatorOperation extends AbstractOperation<SimulatorOperation> {
    private WebProject project_;
    private NativeAppSettings settings_;
    private Target target_;
    private String name_;
    private boolean boot_;
    private String udid_;

    /**
     * The project that this is for, with its settings when it's a
     * {@link NativeAppProject}.
     *
     * @param project the project
     * @return this operation
     * @since 1.11.0
     */
    public SimulatorOperation fromProject(WebProject project) {
        project_ = project;
        if (project instanceof NativeAppProject app) {
            settings_ = app.nativeApp();
        }
        return this;
    }

    /**
     * The settings that name the simulators, for a project that isn't a
     * {@link NativeAppProject}.
     *
     * @param settings the settings
     * @return this operation
     * @since 1.11.0
     */
    public SimulatorOperation settings(NativeAppSettings settings) {
        settings_ = settings;
        return this;
    }

    /**
     * The system whose simulator this finds: ios-sim or vision-sim, or ios and vision for the same.
     *
     * @param target the target
     * @return this operation
     * @since 1.11.0
     */
    public SimulatorOperation target(Target target) {
        target_ = target;
        return this;
    }

    /**
     * The name of the simulator, in place of the one of the settings.
     *
     * @param name the name of the simulator; or {@code null} for the one of the settings
     * @return this operation
     * @since 1.11.0
     */
    public SimulatorOperation name(String name) {
        name_ = name;
        return this;
    }

    /**
     * Whether the simulator is started, and waited for until it's done starting.
     *
     * @param boot whether it's started
     * @return this operation
     * @since 1.11.0
     */
    public SimulatorOperation boot(boolean boot) {
        boot_ = boot;
        return this;
    }

    /**
     * The UDID of the simulator that was found.
     *
     * @return the UDID; or {@code null} before this ran
     * @since 1.11.0
     */
    public String udid() {
        return udid_;
    }

    @Override
    public void execute()
    throws Exception {
        if (null == project_) throw new IllegalStateException("fromProject() wasn't given a project");
        if (null == settings_) throw new IllegalStateException("settings() weren't given");
        if (null == target_ || target_ == Target.MAC || target_ == Target.ANDROID) {
            throw new IllegalArgumentException("A simulator is for ios-sim or vision-sim, or ios and vision for the same");
        }
        Platform.requireMac();
        var vision = target_ == Target.VISION || target_ == Target.VISION_SIMULATOR;
        var name = name_ != null ? name_ : vision ? settings_.visionSimulator() : settings_.simulator();
        var simulator = Simulators.find(target_, name, project_.workDirectory());
        if (boot_) {
            Simulators.boot(simulator.udid(), project_.workDirectory());
        }
        udid_ = simulator.udid();
        if (!silent()) {
            System.out.println(udid_);
        }
    }
}
