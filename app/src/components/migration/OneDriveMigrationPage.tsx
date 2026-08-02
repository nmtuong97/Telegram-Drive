import React, { useState, useEffect } from 'react';
import { invoke } from '@tauri-apps/api/core';
import { listen, UnlistenFn } from '@tauri-apps/api/event';
import { SetupSection } from './SetupSection';
import { ProgressPanel } from './ProgressPanel';
import { ActivityStream } from './ActivityStream';
import { FileTable } from './FileTable';
import { MigrationJob, MigrationJobDetail, ItemProgressPayload, ItemCompletePayload, MigrationActivity, MsAccountInfo, OneDriveItem } from '../../types';
import { useTranslation } from 'react-i18next';
import { Play, RefreshCw, AlertTriangle, ListFilter } from 'lucide-react';

export const OneDriveMigrationPage: React.FC = () => {
    const { t } = useTranslation();
    
    // States
    const [msAccount, setMsAccount] = useState<MsAccountInfo | null>(null);
    const [jobs, setJobs] = useState<MigrationJob[]>([]);
    const [selectedJobId, setSelectedJobId] = useState<number | 'new' | null>(null);
    const [currentDetail, setCurrentDetail] = useState<MigrationJobDetail | null>(null);
    const [activeProgresses, setActiveProgresses] = useState<Record<number, ItemProgressPayload>>({});
    const [activities, setActivities] = useState<MigrationActivity[]>([]);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState<string | null>(null);
    
    // Config state
    const [sourceId, setSourceId] = useState<string>('');
    const [sourcePath, setSourcePath] = useState<string>('');
    const [destId, setDestId] = useState<number | null>(null);
    const [destName, setDestName] = useState<string>('');
    const [localDir, setLocalDir] = useState<string>('');

    // Polling setup
    useEffect(() => {
        let isMounted = true;
        
        const fetchStatus = async () => {
            try {
                // MS Account status
                const msStatus = await invoke<MsAccountInfo | null>('cmd_migration_ms_status');
                if (isMounted) setMsAccount(msStatus);

                // Fetch jobs history
                const jobList = await invoke<MigrationJob[]>('cmd_migration_list_jobs');
                if (isMounted) setJobs(jobList || []);
                
                let targetId: number | undefined;
                if (typeof selectedJobId === 'number') {
                    targetId = selectedJobId;
                } else if (selectedJobId === null) {
                    const resumable = await invoke<number | null>('cmd_migration_get_resumable_job');
                    if (resumable) {
                        targetId = resumable;
                        if (isMounted) setSelectedJobId(resumable);
                    } else if (jobList && jobList.length > 0) {
                        targetId = jobList[0].id;
                        if (isMounted) setSelectedJobId(jobList[0].id);
                    }
                }

                if (targetId) {
                    const detail = await invoke<MigrationJobDetail>('cmd_migration_get_status', { jobId: targetId });
                    if (isMounted) {
                        setCurrentDetail(detail);
                    }
                } else if (selectedJobId === 'new') {
                    if (isMounted) setCurrentDetail(null);
                }
            } catch (err: any) {
                console.error("Status fetch error", err);
            }
        };

        fetchStatus();
        const interval = setInterval(fetchStatus, 2000);
        return () => {
            isMounted = false;
            clearInterval(interval);
        };
    }, [selectedJobId]);

    // Tauri Event listeners for progress
    useEffect(() => {
        let unlistenProgress: UnlistenFn | null = null;
        let unlistenComplete: UnlistenFn | null = null;
        
        const setupListeners = async () => {
            unlistenProgress = await listen<ItemProgressPayload>('migration:item-progress', (event) => {
                setActiveProgresses(prev => ({
                    ...prev,
                    [event.payload.item_id]: { ...event.payload, timestamp: Date.now() }
                }));
            });

            unlistenComplete = await listen<ItemCompletePayload>('migration:item-complete', (event) => {
                const { job_id, item_id, item_name, phase, status, error_message, timestamp } = event.payload;
                setActiveProgresses(prev => {
                    const next = { ...prev };
                    delete next[item_id];
                    return next;
                });
                setActivities(prev => [{
                    id: timestamp,
                    job_id,
                    item_id,
                    item_name,
                    phase: phase as MigrationActivity['phase'],
                    status,
                    attempt: 0,
                    revision: 0,
                    message: error_message || '',
                    created_at: Math.floor(timestamp / 1000),
                }, ...prev].slice(0, 300));
            });
        };
        
        setupListeners();
        return () => {
            if (unlistenProgress) unlistenProgress();
            if (unlistenComplete) unlistenComplete();
        };
    }, []);

    const handleConnectMs = async (clientId?: string, tenant?: string) => {
        setLoading(true);
        try {
            await invoke('cmd_migration_ms_connect', { clientId, tenant });
            const msStatus = await invoke<MsAccountInfo>('cmd_migration_ms_status');
            setMsAccount(msStatus);
        } catch (e: any) {
            setError(e.toString());
        } finally {
            setLoading(false);
        }
    };

    const handleDisconnectMs = async () => {
        setLoading(true);
        try {
            await invoke('cmd_migration_ms_disconnect');
            setMsAccount(null);
        } catch (e: any) {
            setError(e.toString());
        } finally {
            setLoading(false);
        }
    };

    const handleListFolders = async (parentId?: string) => {
        return invoke<OneDriveItem[]>('cmd_migration_get_folder_children', { parentId: parentId || null });
    };

    const handleSetFolder = (folderId: string, path: string) => {
        setSourceId(folderId);
        setSourcePath(path);
    };

    const handleSetTelegram = (dId: number | null, dName: string) => {
        setDestId(dId);
        setDestName(dName);
    };

    const handleSetLocalDir = (dir: string) => {
        setLocalDir(dir);
    };

    const handleStart = async () => {
        const resumableJob = currentDetail?.job;
        if (resumableJob && ['stopped', 'waiting_for_quota', 'failed'].includes(resumableJob.state)) {
            setLoading(true);
            setError(null);
            try {
                await invoke('cmd_migration_resume', { jobId: resumableJob.id });
                const detail = await invoke<MigrationJobDetail>('cmd_migration_get_status', { jobId: resumableJob.id });
                setCurrentDetail(detail);
            } catch (e: any) {
                setError(e.toString());
            } finally {
                setLoading(false);
            }
            return;
        }

        if (!sourceId || !destName || !localDir) {
            setError("Please fill all required settings before starting.");
            return;
        }
        setLoading(true);
        setError(null);
        try {
            const jobId = await invoke<number>('cmd_migration_start', {
                sourceFolderId: sourceId,
                sourceFolderPath: sourcePath,
                telegramDestinationId: destId,
                telegramDestinationName: destName,
                localBackupDir: localDir
            });
            setSelectedJobId(jobId);
            const detail = await invoke<MigrationJobDetail>('cmd_migration_get_status', { jobId });
            setCurrentDetail(detail);
        } catch (e: any) {
            setError(e.toString());
        } finally {
            setLoading(false);
        }
    };

    const handleStop = async () => {
        try {
            await invoke('cmd_migration_stop');
            setActiveProgresses({});
            if (currentDetail?.job?.id) {
                const detail = await invoke<MigrationJobDetail>('cmd_migration_get_status', { jobId: currentDetail.job.id });
                setCurrentDetail(detail);
            }
        } catch (e: any) {
            setError(e.toString());
        }
    };

    const handleRetry = async () => {
        if (!currentDetail?.job?.id) return;
        setLoading(true);
        setError(null);
        try {
            await invoke('cmd_migration_retry_failed', { jobId: currentDetail.job.id });
            const detail = await invoke<MigrationJobDetail>('cmd_migration_get_status', { jobId: currentDetail.job.id });
            setCurrentDetail(detail);
        } catch (e: any) {
            setError(e.toString());
        } finally {
            setLoading(false);
        }
    };
    
    const handleExportCsv = async () => {
        if (!currentDetail?.files) {
            setError("No files to export");
            return;
        }
        
        try {
            const header = ["ID", "Name", "Path", "Size", "Stage", "Last Error", "Updated At"];
            const escapeCsv = (str: string | null | undefined) => {
                if (!str) return '""';
                const s = String(str);
                if (s.includes(',') || s.includes('"') || s.includes('\n')) {
                    return `"${s.replace(/"/g, '""')}"`;
                }
                return s;
            };

            const rows = currentDetail.files.map(f => [
                f.id,
                escapeCsv(f.name),
                escapeCsv(f.path),
                f.size,
                escapeCsv(f.pipeline_stage),
                escapeCsv(f.last_error),
                new Date(f.updated_at * 1000).toISOString()
            ].join(","));

            const csvContent = [header.join(","), ...rows].join("\n");
            
            // Add timestamp to filename to prevent accidental overrides
            const timestamp = new Date().toISOString().replace(/[:.]/g, '-');
            const targetPath = `/Volumes/DATASTORE/Temo/migration_queue_export_${timestamp}.csv`;
            
            await invoke('cmd_migration_export_queue_csv', { 
                csvContent, 
                filePath: targetPath 
            });
            
            alert(`Đã lưu file CSV thành công tại:\n${targetPath}`);
        } catch (e: any) {
            setError(e.toString());
        }
    };
    
    // Determine if we show Setup or Job execution/detail
    const showSetup = selectedJobId === 'new' || !currentDetail;

    return (
        <div className="h-full flex flex-col bg-slate-950 text-slate-200 overflow-hidden">
            {/* Header */}
            <header className="flex-none px-6 py-4 border-b border-slate-800/60 bg-slate-900/50 flex justify-between items-center z-10 backdrop-blur-sm">
                <div className="flex items-center gap-3">
                    <div className="p-2 bg-blue-500/10 rounded-lg border border-blue-500/20">
                        <RefreshCw className="w-5 h-5 text-blue-400" />
                    </div>
                    <div>
                        <h1 className="text-xl font-bold bg-clip-text text-transparent bg-gradient-to-r from-blue-400 to-indigo-300">
                            {t('migration.title', 'OneDrive Migration')}
                        </h1>
                        <p className="text-xs text-slate-500 font-medium">Seamless cloud to telegram sync</p>
                    </div>
                </div>

                {/* Job Selector Dropdown */}
                <div className="flex items-center gap-2 bg-slate-900/90 px-3 py-1.5 rounded-lg border border-slate-800">
                    <ListFilter className="w-4 h-4 text-blue-400" />
                    <span className="text-xs font-semibold text-slate-400">Chọn Tiến Trình:</span>
                    <select
                        value={selectedJobId === 'new' ? 'new' : (selectedJobId ?? currentDetail?.job?.id ?? '')}
                        onChange={(e) => {
                            const val = e.target.value;
                            if (val === 'new') {
                                setSelectedJobId('new');
                                setCurrentDetail(null);
                            } else {
                                const id = Number(val);
                                setSelectedJobId(id);
                            }
                        }}
                        className="bg-slate-950 text-slate-200 text-xs rounded px-2 py-1 border border-slate-700 focus:outline-none focus:border-blue-500 font-medium"
                    >
                        <option value="new">+ Tạo tiến trình mới (New Migration)</option>
                        {jobs.map((j) => (
                            <option key={j.id} value={j.id}>
                                Job #{j.id} [{j.state.toUpperCase()}] - {j.source_folder_path} ➔ {j.telegram_destination_name} ({j.completed_items}/{j.discovered_items} files)
                            </option>
                        ))}
                    </select>
                </div>
            </header>

            <main className="flex-1 overflow-y-auto custom-scrollbar p-6 space-y-6">
                {error && (
                    <div className="p-4 bg-red-500/10 border border-red-500/20 rounded-xl flex items-start gap-3">
                        <AlertTriangle className="w-5 h-5 text-red-400 flex-shrink-0 mt-0.5" />
                        <div className="text-sm text-red-200">{error}</div>
                        <button onClick={() => setError(null)} className="ml-auto text-red-400 hover:text-red-300 text-xs uppercase tracking-wider font-bold">Dismiss</button>
                    </div>
                )}

                {showSetup ? (
                    <div className="max-w-4xl mx-auto space-y-6 animate-in fade-in slide-in-from-bottom-4 duration-500">
                        <SetupSection
                            msAccount={msAccount}
                            loading={loading}
                            sourceFolderPath={sourcePath}
                            telegramDestName={destName}
                            localDir={localDir}
                            onConnectMs={handleConnectMs}
                            onDisconnectMs={handleDisconnectMs}
                            onListOneDriveFolders={handleListFolders}
                            onSetSourceFolder={handleSetFolder}
                            onSetTelegramDest={handleSetTelegram}
                            onSetLocalDir={handleSetLocalDir}
                        />
                        
                        {/* Start Button Area */}
                        {msAccount && sourceId && destName && localDir && (
                            <div className="flex justify-end pt-4 border-t border-slate-800">
                                <button
                                    onClick={handleStart}
                                    disabled={loading}
                                    className="flex items-center gap-2 px-8 py-3 bg-gradient-to-r from-blue-600 to-indigo-600 hover:from-blue-500 hover:to-indigo-500 text-white rounded-xl font-semibold shadow-lg shadow-blue-500/20 transition-all disabled:opacity-50 disabled:cursor-not-allowed transform hover:-translate-y-0.5 active:translate-y-0"
                                >
                                    {loading ? <RefreshCw className="w-5 h-5 animate-spin" /> : <Play className="w-5 h-5" />}
                                    {t('migration.start', 'Start Migration')}
                                </button>
                            </div>
                        )}
                    </div>
                ) : (
                    <div className="grid grid-cols-1 lg:grid-cols-3 gap-6 h-full animate-in fade-in duration-500">
                        {/* Left Column: Progress & Stats */}
                        <div className="lg:col-span-2 space-y-6 flex flex-col h-full">
                            {currentDetail && (
                                <ProgressPanel
                                    detail={currentDetail}
                                    activeProgresses={activeProgresses}
                                    cooldown={null}
                                    onStart={handleStart}
                                    onStop={handleStop}
                                    onRetryAllFailed={handleRetry}
                                />
                            )}
                            
                            {/* File Table / Queue */}
                            <div className="flex-1 min-h-[300px] bg-slate-900/60 rounded-xl border border-slate-800/60 overflow-hidden flex flex-col">
                                <div className="p-4 border-b border-slate-800/60 bg-slate-900/80 flex justify-between items-center">
                                    <h3 className="font-semibold text-slate-300">File Queue</h3>
                                    <button 
                                        onClick={handleExportCsv}
                                        className="px-3 py-1 bg-slate-800 hover:bg-slate-700 text-slate-300 text-sm font-medium rounded-md transition-colors border border-slate-700"
                                    >
                                        Export CSV
                                    </button>
                                </div>
                                <div className="flex-1 overflow-hidden">
                                    {currentDetail?.files && <FileTable files={currentDetail.files} onRetryItem={(itemId) => { console.log("Retry", itemId); }} />}
                                </div>
                            </div>
                        </div>

                        {/* Right Column: Logs */}
                        <div className="flex flex-col h-full space-y-6">
                            <div className="flex-1 bg-slate-900/60 rounded-xl border border-slate-800/60 overflow-hidden flex flex-col">
                                <div className="p-4 border-b border-slate-800/60 bg-slate-900/80">
                                    <h3 className="font-semibold text-slate-300">Activity Log</h3>
                                </div>
                                <div className="flex-1 overflow-y-auto custom-scrollbar p-2">
                                    <ActivityStream entries={activities} />
                                </div>
                            </div>
                        </div>
                    </div>
                )}
            </main>
        </div>
    );
};
