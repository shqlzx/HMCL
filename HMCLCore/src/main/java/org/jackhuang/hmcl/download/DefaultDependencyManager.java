/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2019  huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.jackhuang.hmcl.download;

import org.jackhuang.hmcl.download.forge.ForgeInstallTask;
import org.jackhuang.hmcl.download.forge.ForgeRemoteVersion;
import org.jackhuang.hmcl.download.game.*;
import org.jackhuang.hmcl.download.liteloader.LiteLoaderInstallTask;
import org.jackhuang.hmcl.download.liteloader.LiteLoaderRemoteVersion;
import org.jackhuang.hmcl.download.optifine.OptiFineInstallTask;
import org.jackhuang.hmcl.download.optifine.OptiFineRemoteVersion;
import org.jackhuang.hmcl.game.DefaultGameRepository;
import org.jackhuang.hmcl.game.Version;
import org.jackhuang.hmcl.task.ParallelTask;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.task.TaskResult;
import org.jackhuang.hmcl.util.function.ExceptionalFunction;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Note: This class has no state.
 *
 * @author huangyuhui
 */
public class DefaultDependencyManager extends AbstractDependencyManager {

    private final DefaultGameRepository repository;
    private final DownloadProvider downloadProvider;
    private final DefaultCacheRepository cacheRepository;

    public DefaultDependencyManager(DefaultGameRepository repository, DownloadProvider downloadProvider, DefaultCacheRepository cacheRepository) {
        this.repository = repository;
        this.downloadProvider = downloadProvider;
        this.cacheRepository = cacheRepository;
    }

    @Override
    public DefaultGameRepository getGameRepository() {
        return repository;
    }

    @Override
    public DownloadProvider getDownloadProvider() {
        return downloadProvider;
    }

    @Override
    public DefaultCacheRepository getCacheRepository() {
        return cacheRepository;
    }

    @Override
    public GameBuilder gameBuilder() {
        return new DefaultGameBuilder(this);
    }

    @Override
    public Task checkGameCompletionAsync(Version version) {
        return new ParallelTask(
                Task.ofThen(() -> {
                    if (!repository.getVersionJar(version).exists())
                        return new GameDownloadTask(this, null, version);
                    else
                        return null;
                }),
                new GameAssetDownloadTask(this, version, GameAssetDownloadTask.DOWNLOAD_INDEX_IF_NECESSARY),
                new GameLibrariesTask(this, version)
        );
    }

    @Override
    public Task checkLibraryCompletionAsync(Version version) {
        return new GameLibrariesTask(this, version);
    }

    @Override
    public TaskResult<Version> installLibraryAsync(String gameVersion, Version version, String libraryId, String libraryVersion) {
        VersionList<?> versionList = getVersionList(libraryId);
        return versionList.loadAsync(gameVersion, getDownloadProvider())
                .thenCompose(() -> installLibraryAsync(version, versionList.getVersion(gameVersion, libraryVersion)
                        .orElseThrow(() -> new IllegalStateException("Remote library " + libraryId + " has no version " + libraryVersion))));
    }

    @Override
    public TaskResult<Version> installLibraryAsync(Version oldVersion, RemoteVersion libraryVersion) {
        TaskResult<Version> task;
        if (libraryVersion instanceof ForgeRemoteVersion)
            task = new ForgeInstallTask(this, oldVersion, (ForgeRemoteVersion) libraryVersion);
        else if (libraryVersion instanceof LiteLoaderRemoteVersion)
            task = new LiteLoaderInstallTask(this, oldVersion, (LiteLoaderRemoteVersion) libraryVersion);
        else if (libraryVersion instanceof OptiFineRemoteVersion)
            task = new OptiFineInstallTask(this, oldVersion, (OptiFineRemoteVersion) libraryVersion);
        else
            throw new IllegalArgumentException("Remote library " + libraryVersion + " is unrecognized.");
        return task
                .thenCompose(LibrariesUniqueTask::new)
                .thenCompose(MaintainTask::new)
                .thenCompose(newVersion -> new VersionJsonSaveTask(repository, newVersion));
    }

    public ExceptionalFunction<Version, TaskResult<Version>, ?> installLibraryAsync(RemoteVersion libraryVersion) {
        return version -> installLibraryAsync(version, libraryVersion);
    }

    public Task installLibraryAsync(Version oldVersion, Path installer) {
        return Task
                .of(() -> {
                })
                .thenCompose(() -> {
                    try {
                        return ForgeInstallTask.install(this, oldVersion, installer);
                    } catch (IOException ignore) {
                    }

                    try {
                        return OptiFineInstallTask.install(this, oldVersion, installer);
                    } catch (IOException ignore) {
                    }

                    throw new UnsupportedOperationException("Library cannot be recognized");
                })
                .thenCompose(LibrariesUniqueTask::new)
                .thenCompose(MaintainTask::new)
                .thenCompose(newVersion -> new VersionJsonSaveTask(repository, newVersion));
    }
}
