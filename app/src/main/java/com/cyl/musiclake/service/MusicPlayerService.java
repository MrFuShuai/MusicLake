package com.cyl.musiclake.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.support.annotation.Nullable;
import android.support.annotation.RequiresApi;
import android.support.v4.media.app.NotificationCompat;
import android.support.v4.media.session.MediaButtonReceiver;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;

import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.target.SimpleTarget;
import com.bumptech.glide.request.transition.Transition;
import com.cyl.musiclake.IMusicService;
import com.cyl.musiclake.R;
import com.cyl.musiclake.api.GlideApp;
import com.cyl.musiclake.data.model.Music;
import com.cyl.musiclake.data.source.SongQueueLoader;
import com.cyl.musiclake.ui.main.MainActivity;
import com.cyl.musiclake.utils.Constants;
import com.cyl.musiclake.utils.CoverLoader;
import com.cyl.musiclake.utils.PreferencesUtils;
import com.cyl.musiclake.utils.SystemUtils;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static android.support.v4.app.NotificationCompat.Builder;

/**
 * 作者：yonglong on 2016/8/11 19:16
 * 邮箱：643872807@qq.com
 * 版本：2.5 播放service
 */
public class MusicPlayerService extends Service {
    private static final String TAG = "MusicPlayerService";

    public static final String ACTION_SERVICE = "com.cyl.music_hnust.service";// 广播标志
    public static final String ACTION_NEXT = "com.cyl.music_hnust.notify.next";// 下一首广播标志
    public static final String ACTION_PREV = "com.cyl.music_hnust.notify.prev";// 上一首广播标志
    public static final String PLAY_STATE_CHANGED = "com.cyl.music_hnust.play_state";// 播放广播标志
    public static final String ACTION_UPDATE = "com.cyl.music_hnust.notify.update";// 播放广播标志
    public static final String PLAYLIST_CHANGED = "com.cyl.music_hnust.playlist";
    public static final String TRACK_ERROR = "com.cyl.music_hnust.error";
    public static final String REFRESH = "com.cyl.music_hnust.refresh";
    public static final String PLAYLIST_CLEAR = "com.cyl.music_hnust.playlist_clear";
    public static final String META_CHANGED = "com.cyl.music_hnust.metachanged";

    public static final int TRACK_WENT_TO_NEXT = 2; //下一首
    public static final int RELEASE_WAKELOCK = 3; //播放完成
    public static final int TRACK_PLAY_ENDED = 4; //播放完成
    public static final int TRACK_PLAY_ERROR = 5; //播放出错
    public static final int QQ_MUSIC_URL = 6; //播放出错
    public static final int PREPARE_ASYNC_UPDATE = 7; //PrepareAsync装载进程
    public static final int PREPARE_ASYNC_ENDED = 8; //PrepareAsync异步装载完成

    private static final int NOTIFY_MODE_NONE = 0;
    private static final int NOTIFY_MODE_FOREGROUND = 1;
    private static int mNotifyMode = 0;
    private final int NOTIFICATION_ID = 0x123;
    private long mNotificationPostTime = 0;

    private static final boolean DEBUG = true;

    private MusicPlayerEngine mPlayer = null;
    public PowerManager.WakeLock mWakeLock;

    //工作线程和Handler
    private MusicPlayerHandler mHandler;
    private HandlerThread mWorkThread;
    //主线程Handler
    private Handler mMainHandler;

    private static Music mPlayingMusic = null;
    private List<Music> mPlaylist = new ArrayList<>();
    private int mPlayingPos = -1;

    //播放模式：0顺序播放、1随机播放、2单曲循环
    private int mRepeatMode;
    private final int PLAY_MODE_RANDOM = 0;
    private final int PLAY_MODE_LOOP = 1;
    private final int PLAY_MODE_REPEAT = 2;
    //广播接收者
    ServiceReceiver mServiceReceiver;
    HeadsetReceiver mHeadsetReceiver;

    private NotificationManager mNotificationManager;
    private Notification mNotification;
    private IMusicServiceStub mBindStub = new IMusicServiceStub(this);
    private MediaSessionCompat mSession;
    private boolean isRunningForeground = false;
    private boolean isMusicPlaying = false;

    private class MusicPlayerHandler extends Handler {
        private final WeakReference<MusicPlayerService> mService;
        private float mCurrentVolume = 1.0f;

        public MusicPlayerHandler(final MusicPlayerService service, final Looper looper) {
            super(looper);
            mService = new WeakReference<MusicPlayerService>(service);
        }

        @Override
        public void handleMessage(Message msg) {
            super.handleMessage(msg);
            final MusicPlayerService service = mService.get();
            synchronized (service) {
                switch (msg.what) {
                    case TRACK_WENT_TO_NEXT://mPlayer播放完成后下一首
                        service.next();
                        break;
                    case TRACK_PLAY_ENDED://mPlayer播放完成后结束
                        service.next();
//                        service.seekTo(0);
//                        service.playMusic(mPlayingPos);
//                        if (service.mRepeatMode == PLAY_MODE_REPEAT) {
//                            service.seekTo(0);
//                            mPlayer.start();
//                        } else {
//                            service.next();
//                        }
                        break;
                    case TRACK_PLAY_ERROR://mPlayer播放错误
                        break;
                    case RELEASE_WAKELOCK://释放电源锁
                        service.mWakeLock.release();
                        break;
                    case PREPARE_ASYNC_UPDATE:
                        int percent = (int) msg.obj;
                        Log.e(TAG, "Loading ... " + percent);
                        break;
                    case PREPARE_ASYNC_ENDED:
                        Log.e(TAG, "PREPARE_ASYNC_ENDED");
                        notifyChange(META_CHANGED);
                        break;
                }
            }

        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        //初始化广播
        initReceiver();
        //初始化参数
        initConfig();
        //初始化音乐播放服务
        initMediaPlayer();
        //初始化电话监听服务
        initTelephony();
        setUpMediaSession();
        //初始化通知
        initNotify();
    }

    /**
     * 参数配置，锁屏
     */
    private void initConfig() {
        mMainHandler = new Handler();

        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        mWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PlayerWakelockTag");
    }


    /**
     * 初始化和设置MediaSessionCompat
     * MediaSessionCompat用于告诉系统及其他应用当前正在播放的内容,以及接收什么类型的播放控制
     */
    private void setUpMediaSession() {
        mSession = new MediaSessionCompat(this, "Listener");
        mSession.setCallback(new MediaSessionCompat.Callback() {
            @Override
            public void onPause() {
                pause();
//                mPausedByTransientLossOfFocus = false;
            }

            @Override
            public void onPlay() {
                playPause();
            }

            @Override
            public void onSeekTo(long pos) {
                seekTo((int) pos);
            }

            @Override
            public void onSkipToNext() {
                next();
            }

            @Override
            public void onSkipToPrevious() {
                prev();
            }

            @Override
            public void onStop() {
                pause();
//                mPausedByTransientLossOfFocus = false;
                seekTo(0);
                releaseServiceUiAndStop();
            }
        });
        mSession.setFlags(MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);
    }

    private void releaseServiceUiAndStop() {

    }


    /**
     * 初始化电话监听服务
     */
    private void initTelephony() {
        TelephonyManager telephonyManager = (TelephonyManager) this
                .getSystemService(Context.TELEPHONY_SERVICE);// 获取电话通讯服务
        telephonyManager.listen(new ServicePhoneStateListener(),
                PhoneStateListener.LISTEN_CALL_STATE);// 创建一个监听对象，监听电话状态改变事件
    }

    /**
     * 初始化音乐播放服务
     */
    private void initMediaPlayer() {
        //初始化工作线程
        mWorkThread = new HandlerThread("MusicPlayerThread");
        mWorkThread.start();

        mHandler = new MusicPlayerHandler(this, mWorkThread.getLooper());

        mPlayer = new MusicPlayerEngine(this);
        mPlayer.setHandler(mHandler);
    }

    /**
     * 初始化广播
     */
    private void initReceiver() {
        //实例化过滤器，设置广播
        mServiceReceiver = new ServiceReceiver();
        mHeadsetReceiver = new HeadsetReceiver();
        IntentFilter intentFilter = new IntentFilter(ACTION_SERVICE);
        intentFilter.addAction(ACTION_NEXT);
        intentFilter.addAction(ACTION_PREV);
        intentFilter.addAction(PLAY_STATE_CHANGED);
        //注册广播
        registerReceiver(mServiceReceiver, intentFilter);
        registerReceiver(mHeadsetReceiver, intentFilter);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "Got new intent " + intent + ", startId = " + startId);
//        mServiceStartId = startId;
        if (intent != null) {
            final String action = intent.getAction();

//            if (SHUTDOWN.equals(action)) {
//                mShutdownScheduled = false;
//                releaseServiceUiAndStop();
//                return START_NOT_STICKY;
//            }

            handleCommandIntent(intent);
        }

//        scheduleDelayedShutdown();

//        if (intent != null && intent.getBooleanExtra(FROM_MEDIA_BUTTON, false)) {
//            MediaButtonIntentReceiver.completeWakefulIntent(intent);
//        }
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return mBindStub;
    }

    /**
     * 下一首
     */
    private void next() {
        mPlayingPos = getNextPosition();
        playMusic(mPlayingPos);
    }

    /**
     * 上一首
     */
    private void prev() {
        mPlayingPos = getPreviousPosition();
        Log.e(TAG, "mPlayingPos:" + mPlayingPos);
        playMusic(mPlayingPos);
    }

    /**
     * 获取下一首位置
     *
     * @return
     */
    private int getNextPosition() {
        if (mPlaylist == null || mPlaylist.isEmpty()) {
            return -1;
        }
        if (mPlaylist.size() == 1) {
            return 0;
        }
        mRepeatMode = PreferencesUtils.getPlayMode();
        if (mRepeatMode == PLAY_MODE_REPEAT) {
            if (mPlayingPos < 0) {
                return 0;
            }
        } else if (mRepeatMode == PLAY_MODE_LOOP) {
            if (mPlayingPos == mPlaylist.size() - 1) {
                mPlayingPos = 0;
            } else if (mPlayingPos < mPlaylist.size() - 1) {
                mPlayingPos += 1;
            }
        } else if (mRepeatMode == PLAY_MODE_RANDOM) {
            mPlayingPos = new Random().nextInt(mPlaylist.size());
        }
        return mPlayingPos;
    }

    /**
     * 获取上一首位置
     *
     * @return
     */
    private int getPreviousPosition() {
        if (mPlaylist == null || mPlaylist.isEmpty()) {
            return -1;
        }
        if (mPlaylist.size() == 1) {
            return 0;
        }
        mRepeatMode = PreferencesUtils.getPlayMode();
        if (mRepeatMode == PLAY_MODE_REPEAT) {
            if (mPlayingPos < 0) {
                return 0;
            }
        } else if (mRepeatMode == PLAY_MODE_LOOP) {
            if (mPlayingPos == 0) {
                mPlayingPos = mPlaylist.size() - 1;
            } else if (mPlayingPos > 0) {
                mPlayingPos -= 1;
            }
        } else if (mRepeatMode == PLAY_MODE_RANDOM) {
            mPlayingPos = new Random().nextInt(mPlaylist.size());
        }
        return mPlayingPos;
    }

    /**
     * 根据位置播放音乐
     *
     * @param position
     */
    private void playMusic(int position) {
        mPlayingPos = position;
        if (mPlaylist == null || mPlaylist.isEmpty()) {
            return;
        }
        if (mPlayingPos == -1) {
            return;
        }
        if (mPlaylist.get(mPlayingPos) == null) {
            return;
        }
        Log.e(TAG, "position" + position);
        playMusic(mPlaylist.get(position));
    }

    /**
     * 播放音乐
     *
     * @param music
     */
    public void playMusic(Music music) {
        Log.e(TAG, music.toString());
//        if (music.getType() == Music.Type.QQ && (music.getUri() == null || music.getUri().length() > 0)) {
//        } else {
        mPlayingMusic = music;
        mPlayer.setDataSource(mPlayingMusic.getUri());
//        }
        isMusicPlaying = true;
        updateNotification();
    }


    /**
     * 播放暂停
     */
    private void playPause() {
        if (isPlaying()) {
            pause();
        } else if (isPause()) {
            notifyChange(META_CHANGED);
            mPlayer.start();
            isMusicPlaying = true;
            updateNotification();
        } else {
            playMusic(mPlayingPos);
        }
    }

    /**
     * 暂停播放
     */
    public void pause() {
        if (!isPlaying()) {
            return;
        }
        isMusicPlaying = false;
        mPlayer.pause();
        notifyChange(META_CHANGED);
        updateNotification();
    }

    /**
     * 是否正在播放音乐
     *
     * @return
     */
    public boolean isPlaying() {
        if (mPlayer == null) {
            isMusicPlaying = false;
        }
        return isMusicPlaying;
    }

    /**
     * 判断是否是暂停
     *
     * @return
     */
    public boolean isPause() {
        if (mPlayer == null) {
            isMusicPlaying = false;
        }
        return !isMusicPlaying;
    }


    /**
     * 跳到输入的进度
     */
    public void seekTo(int msec) {
        if (mPlayer != null && mPlayingMusic != null) {
            mPlayer.seek(msec);
        }
    }

    @Override
    public boolean onUnbind(Intent intent) {
        return true;
    }


    private void refresh() {
        PreferencesUtils.saveCurrentSongId(mPlayingPos);
        SongQueueLoader.updateQueue(this, mPlaylist);
    }

    /**
     * 获取正在播放歌曲的位置
     *
     * @return
     */
    public int getmPlayingPosition() {
        return mPlayingPos;
    }

    /**
     * 获取正在播放的歌曲[本地|网络]
     */
    public void removeFromQueue(int position) {
        Log.e(TAG, position + "---" + mPlayingPos + "---" + mPlaylist.size());
        if (position == mPlayingPos) {
            mPlaylist.remove(position);
            playMusic(position);
        } else if (position > mPlayingPos) {
            mPlaylist.remove(position);
        } else if (position < mPlayingPos) {
            mPlaylist.remove(position);
            mPlayingPos = mPlayingPos - 1;
        }
        notifyChange(META_CHANGED);
        notifyChange(REFRESH);
    }

    /**
     * 获取正在播放的歌曲[本地|网络]
     */
    public void clearQueue() {
        mPlayingMusic = null;
        mPlaylist.clear();
        mPlayer.stop();
        notifyChange(META_CHANGED);
        notifyChange(PLAYLIST_CLEAR);
    }

    /**
     * 获取正在播放时间
     */
    public long getCurrentPosition() {
        if (mPlayingMusic != null) {
            return mPlayer.position();
        } else {
            return 0;
        }
    }

    /**
     * 获取时长
     */
    public long getDuration() {
        if (mPlayingMusic != null) {
            return mPlayer.duration();
        } else {
            return 0;
        }
    }

    int mNextPlayPos = -1;

    /**
     * 设置下首播放曲目的位置,并设置mplayer下次播放的datasource
     *
     * @param position
     */
    private void setNextTrack(int position) {
        mNextPlayPos = position;
        if (DEBUG) Log.d(TAG, "setNextTrack: next play position = " + mNextPlayPos);
        if (mNextPlayPos >= 0 && mPlaylist != null && mNextPlayPos < mPlaylist.size()) {
            mPlayer.setNextDataSource(mPlaylist.get(mNextPlayPos).getUri());
        } else {
            mPlayer.setNextDataSource(null);
        }
    }


    private void notifyChange(final String what) {
        if (DEBUG) Log.d(TAG, "notifyChange: what = " + what);
//        if (what.equals(POSITION_CHANGED)) {
//            return;
//        }

        final Intent intent = new Intent(what);
        intent.putExtra("artist", getArtistName());
        intent.putExtra("album", getAlbumName());
        intent.putExtra("track", getTitle());
        intent.putExtra("playing", isPlaying());
        sendBroadcast(intent);
    }


    public String getTitle() {
        if (mPlayingMusic != null) {
            return mPlayingMusic.getTitle();
        }
        return null;
    }

    private String getArtistName() {
        if (mPlayingMusic != null) {
            return mPlayingMusic.getAlbum();
        }
        return null;
    }

    private String getAlbumName() {
        if (mPlayingMusic != null) {
            return mPlayingMusic.getArtist();
        }
        return null;
    }

    private Music getPlayingMusic() {
        if (mPlayingMusic != null) {
            return mPlayingMusic;
        }
        return null;
    }


    private void setPlayQueue(List<Music> playQueue) {
        mPlaylist.clear();
        mPlaylist.addAll(playQueue);
    }


    private List<Music> getPlayQueue() {
        if (mPlaylist.size() > 0) {
            return mPlaylist;
        }
        return mPlaylist;
    }


    private int getPlayPosition() {
        if (mPlayingPos >= 0) {
            return mPlayingPos;
        } else return 0;
    }

    /**
     * 初始化通知栏
     */
    private void initNotify() {
        mNotificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        final String albumName = getAlbumName();
        final String artistName = getArtistName();
        final boolean isPlaying = isPlaying();
        String text = TextUtils.isEmpty(albumName)
                ? artistName : artistName + " - " + albumName;

        int playButtonResId = isPlaying
                ? R.drawable.ic_pause : R.drawable.ic_play_arrow_white_18dp;

        Intent nowPlayingIntent = new Intent(this, MainActivity.class);
        nowPlayingIntent.setAction(Constants.DEAULT_NOTIFICATION);
        PendingIntent clickIntent = PendingIntent.getActivity(this, 0, nowPlayingIntent, PendingIntent.FLAG_UPDATE_CURRENT);
        String coverUrl = null;
        if (mPlayingMusic != null && mPlayingMusic.getType() == Music.Type.LOCAL
                && mPlayingMusic.getAlbumId() != -1) {
            coverUrl = CoverLoader.getInstance().getCoverUri(this, mPlayingMusic.getAlbumId());
        } else if (mPlayingMusic != null) {
            coverUrl = mPlayingMusic.getCoverUri();
        }
        if (mNotificationPostTime == 0) {
            mNotificationPostTime = System.currentTimeMillis();
        }
        Builder builder = new Builder(this, initChannelId())
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(clickIntent)
                .setContentTitle(getTitle())
                .setContentText(text)
                .setWhen(mNotificationPostTime)
                .addAction(R.drawable.ic_skip_previous,
                        "",
                        retrievePlaybackAction(ACTION_PREV))
                .addAction(playButtonResId, "",
                        retrievePlaybackAction(PLAY_STATE_CHANGED))
                .addAction(R.drawable.ic_skip_next,
                        "",
                        retrievePlaybackAction(ACTION_NEXT))
                .setDeleteIntent(MediaButtonReceiver.buildMediaButtonPendingIntent(
                        this, PlaybackStateCompat.ACTION_STOP));

        GlideApp.with(this)
                .asBitmap()
                .load(coverUrl)
                .error(R.drawable.default_cover)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .into(new SimpleTarget<Bitmap>() {
                    @Override
                    public void onResourceReady(Bitmap resource, Transition<? super Bitmap> transition) {
                        builder.setLargeIcon(resource);
                    }
                });
        if (SystemUtils.isJellyBeanMR1()) {
            builder.setShowWhen(false);
        }

        if (SystemUtils.isLollipop()) {
            //线控
            isRunningForeground = true;

            builder.setVisibility(Notification.VISIBILITY_PUBLIC);
            NotificationCompat.MediaStyle style = new NotificationCompat.MediaStyle()
                    .setMediaSession(mSession.getSessionToken())
                    .setShowActionsInCompactView(0, 1, 2, 3);
            builder.setStyle(style);
        }
        mNotification = builder.build();
    }


    /**
     * 创建Notification ChannelID
     *
     * @return
     */
    private String initChannelId() {
        // 通知渠道的id
        String id = "music_lake_01";
        // 用户可以看到的通知渠道的名字.
        CharSequence name = "音乐湖";
        // 用户可以看到的通知渠道的描述
        String description = "通知栏播放控制";
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            int importance = NotificationManager.IMPORTANCE_LOW;
            NotificationChannel mChannel = null;
            mChannel = new NotificationChannel(id, name, importance);
            mChannel.setDescription(description);
            mChannel.enableLights(false);
            mChannel.enableVibration(false);
            //最后在notificationmanager中创建该通知渠道
            mNotificationManager.createNotificationChannel(mChannel);
        }
        return id;
    }

    private PendingIntent retrievePlaybackAction(final String action) {
        Intent intent = new Intent(action);
        intent.setComponent(new ComponentName(this, MusicPlayerService.class));
        return PendingIntent.getService(this, 0, intent, 0);
    }

    public String getAudioId() {
        if (mPlayingMusic != null) {
            return mPlayingMusic.getId();
        } else {
            return null;
        }
    }

    /**
     * 电话监听
     */
    private class ServicePhoneStateListener extends PhoneStateListener {
        @Override
        public void onCallStateChanged(int state, String incomingNumber) {
            // TODO Auto-generated method stub
            switch (state) {
                case TelephonyManager.CALL_STATE_OFFHOOK:   //通话状态
                case TelephonyManager.CALL_STATE_RINGING:   //通话状态
                    pause();
                    break;
            }
        }
    }

    /**
     * 更新状态栏通知
     */
    private void updateNotification() {
        final int newNotifyMode;
        if (isPlaying()) {
            newNotifyMode = NOTIFY_MODE_FOREGROUND;
        } else {
            newNotifyMode = NOTIFY_MODE_NONE;
        }


        startForeground(NOTIFICATION_ID, mNotification);
        mNotificationManager.notify(NOTIFICATION_ID, mNotification);

        if (mNotifyMode != newNotifyMode) {
            if (mNotifyMode == NOTIFY_MODE_FOREGROUND) {
                stopForeground(true);
            } else if (newNotifyMode == NOTIFY_MODE_NONE) {
                mNotificationManager.cancel(NOTIFICATION_ID);
                mNotificationPostTime = 0;
            }
        }
        initNotify();
        startForeground(NOTIFICATION_ID, mNotification);
    }


    /**
     * 取消通知
     */
    private void cancelNotification() {
        stopForeground(true);
        mNotificationManager.cancel(NOTIFICATION_ID);
        mNotifyMode = NOTIFY_MODE_NONE;
        isRunningForeground = false;
    }

    /**
     * Service broadcastReceiver
     */
    private class ServiceReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.i(TAG, intent.getAction().toString());
            handleCommandIntent(intent);
        }
    }


    /**
     * @param intent
     */
    private void handleCommandIntent(Intent intent) {
        final String action = intent.getAction();
        if (ACTION_NEXT.equals(action)) {
            next();
        } else if (ACTION_PREV.equals(action)) {
            prev();
        } else if (PLAY_STATE_CHANGED.equals(action)) {
            playPause();
        }
    }

//    /**
//     * 耳机插入广播接收器
//     */
//    public class HeadsetPlugInReceiver extends BroadcastReceiver {
//
//        final IntentFilter filter;
//
//        public HeadsetPlugInReceiver() {
//            filter = new IntentFilter();
//
//            if (Build.VERSION.SDK_INT >= 21) {
//                filter.addAction(AudioManager.ACTION_HEADSET_PLUG);
//            } else {
//                filter.addAction(Intent.ACTION_HEADSET_PLUG);
//            }
//        }
//
//        @Override
//        public void onReceive(Context context, Intent intent) {
//            if (intent != null && intent.hasExtra("state")
//                    && AppConfigs.isResumeAudioWhenPlugin) {
//
//                //通过判断 "state" 来知道状态
//                final boolean isPlugIn = intent.getExtras().getInt("state") == 1;
//
//            }
//        }
//
//    }

    /**
     * 耳机拔出广播接收器
     */
    private class HeadsetReceiver extends BroadcastReceiver {

        final IntentFilter filter;
        final BluetoothAdapter bluetoothAdapter;

        public HeadsetReceiver() {
            filter = new IntentFilter();
            filter.addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY); //有线耳机拔出变化
            filter.addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED); //蓝牙耳机连接变化

            bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            if (isRunningForeground) {
                //当前是正在运行的时候才能通过媒体按键来操作音频
                switch (intent.getAction()) {
                    case BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED:
                        if (bluetoothAdapter != null &&
                                BluetoothProfile.STATE_DISCONNECTED == bluetoothAdapter.getProfileConnectionState(BluetoothProfile.HEADSET) &&
                                isPlaying()) {
                            //蓝牙耳机断开连接 同时当前音乐正在播放 则将其暂停
                            pause();
                        }
                        break;
                    case AudioManager.ACTION_AUDIO_BECOMING_NOISY:
                        if (isPlaying()) {
                            //有线耳机断开连接 同时当前音乐正在播放 则将其暂停
                            pause();
                        }
                        break;
                }
            }
        }

    }


    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
    @Override
    public void onDestroy() {
        super.onDestroy();
        //释放mPlayer
        if (mPlayer != null) {
            mPlayer.stop();
            mPlayer.release();
            mPlayer = null;
        }

        // 释放Handler资源
        if (mHandler != null) {
            mHandler.removeCallbacksAndMessages(null);
            mHandler = null;
        }

        // 释放工作线程资源
        if (mWorkThread != null && mWorkThread.isAlive()) {
            mWorkThread.quitSafely();
            mWorkThread.interrupt();
            mWorkThread = null;
        }

        mWakeLock.release();

        SongQueueLoader.updateQueue(this, mPlaylist);
        PreferencesUtils.saveCurrentSongId(mPlayingPos);
        cancelNotification();

        //注销广播
        unregisterReceiver(mServiceReceiver);
        unregisterReceiver(mHeadsetReceiver);


        stopSelf();
        Log.d("TAG", "ondestory");
    }

    private class IMusicServiceStub extends IMusicService.Stub {
        private final WeakReference<MusicPlayerService> mService;

        private IMusicServiceStub(final MusicPlayerService service) {
            mService = new WeakReference<MusicPlayerService>(service);
        }

        @Override
        public void playOnline(Music music) throws RemoteException {
            mService.get().playMusic(music);
        }

        @Override
        public void play(int id) throws RemoteException {
            mService.get().playMusic(id);
        }

        @Override
        public void playPause() throws RemoteException {
            mService.get().playPause();
        }

        @Override
        public void prev() throws RemoteException {
            mService.get().prev();
        }

        @Override
        public void next() throws RemoteException {
            mService.get().next();
        }

        @Override
        public void refresh() throws RemoteException {
            mService.get().refresh();
        }

        @Override
        public void setLoopMode(int loopmode) throws RemoteException {
        }

        @Override
        public void seekTo(int ms) throws RemoteException {
            mService.get().seekTo(ms);
        }

        @Override
        public String getSongName() throws RemoteException {
            return mService.get().getTitle();
        }

        @Override
        public String getSongArtist() throws RemoteException {
            return mService.get().getArtistName();
        }

        @Override
        public Music getPlayingMusic() throws RemoteException {
            return mService.get().getPlayingMusic();
        }

        @Override
        public void setPlayList(List<Music> playlist) throws RemoteException {
            mService.get().setPlayQueue(playlist);
        }

        @Override
        public List<Music> getPlayList() throws RemoteException {
            return mService.get().getPlayQueue();
        }

        @Override
        public void removeFromQueue(int position) throws RemoteException {
            mService.get().removeFromQueue(position);
        }

        @Override
        public void clearQueue() throws RemoteException {
            mService.get().clearQueue();
        }

        @Override
        public int position() throws RemoteException {
            return mService.get().getPlayPosition();
        }

        @Override
        public int getDuration() throws RemoteException {
            return (int) mService.get().getDuration();
        }

        @Override
        public int getCurrentPosition() throws RemoteException {
            return (int) mService.get().getCurrentPosition();
        }


        @Override
        public boolean isPlaying() throws RemoteException {
            return mService.get().isPlaying();
        }

        @Override
        public boolean isPause() throws RemoteException {
            return mService.get().isPause();
        }

    }


}
