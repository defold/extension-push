#if defined(DM_PLATFORM_ANDROID)
#include <jni.h>
#include <unistd.h>
#include <stdlib.h>
#include <dmsdk/sdk.h>
#include "push_utils.h"

#define LIB_NAME "push"

static JNIEnv* Attach()
{
    JNIEnv* env;
    dmGraphics::GetNativeAndroidJavaVM()->AttachCurrentThread(&env, NULL);
    return env;
}

static void Detach()
{
    dmGraphics::GetNativeAndroidJavaVM()->DetachCurrentThread();
}

struct ScheduledNotification
{
    int32_t id;
    uint64_t timestamp; // in microseconds
    char* title;
    char* message;
    char* payload;
    int priority;
};

struct Push
{
    Push()
    {
        memset(this, 0, sizeof(*this));
        m_ScheduledNotifications.SetCapacity(8);
    }

    jobject              m_Push;
    jobject              m_PushJNI;
    jmethodID            m_Start;
    jmethodID            m_Stop;
    jmethodID            m_FlushStored;
    jmethodID            m_Register;
    jmethodID            m_Schedule;
    jmethodID            m_Cancel;

    dmScript::LuaCallbackInfo* m_Callback;
    dmScript::LuaCallbackInfo* m_Listener;
    dmPush::CommandQueue m_CommandQueue;

    dmArray<ScheduledNotification> m_ScheduledNotifications;
};

static Push g_Push;

static int Push_Register(lua_State* L)
{
    DM_LUA_STACK_CHECK(L, 0);

    if (g_Push.m_Callback)
        dmScript::DestroyCallback(g_Push.m_Callback);

    // NOTE: We ignore argument one. Only for iOS
    g_Push.m_Callback = dmScript::CreateCallback(L, 2);

    JNIEnv* env = Attach();
    env->CallVoidMethod(g_Push.m_Push, g_Push.m_Register, dmGraphics::GetNativeAndroidActivity());
    Detach();

    return 0;
}

static int Push_SetListener(lua_State* L)
{
    DM_LUA_STACK_CHECK(L, 0);

    if (g_Push.m_Listener)
        dmScript::DestroyCallback(g_Push.m_Listener);

    g_Push.m_Listener = dmScript::CreateCallback(L, 1);

    // Flush stored notifications stored on Java side
    JNIEnv* env = Attach();
    env->CallVoidMethod(g_Push.m_Push, g_Push.m_FlushStored);
    Detach();

    return 0;
}

static int Push_Schedule(lua_State* L)
{
    int top = lua_gettop(L);

    int seconds = luaL_checkinteger(L, 1);
    if (seconds < 0)
    {
        lua_pushnil(L);
        lua_pushstring(L, "invalid seconds argument");
        return 2;
    }

    const char* title = luaL_checkstring(L, 2);
    const char* message = luaL_checkstring(L, 3);

    // param: payload
    const char* payload = 0;
    if (top > 3) {
        payload = luaL_checkstring(L, 4);

        // Verify that the payload is valid and can be delivered later on.
        char payload_err[128];
        if (!dmPush::VerifyPayload(L, payload, payload_err, sizeof(payload_err))) {
            lua_pushnil(L);
            lua_pushstring(L, payload_err);
            return 2;
        }
    }

    // param: notification_settings
    int priority = 2;
    // char* icon = 0;
    // char* sound = 0;
    if (top > 4) {
        luaL_checktype(L, 5, LUA_TTABLE);

        // priority
        lua_pushstring(L, "priority");
        lua_gettable(L, 5);
        if (lua_isnumber(L, -1)) {
            priority = lua_tointeger(L, -1);

            if (priority < -2) {
                priority = -2;
            } else if (priority > 2) {
                priority = 2;
            }
        }
        lua_pop(L, 1);

        /*

        // icon
        There is now way of automatically bundle files inside the .app folder (i.e. skipping
        archiving them inside the .darc), but to have custom notification sounds they need to
        be accessable from the .app folder.

        lua_pushstring(L, "icon");
        lua_gettable(L, 5);
        if (lua_isstring(L, -1)) {
            icon = lua_tostring(L, -1);
        }
        lua_pop(L, 1);

        // sound
        lua_pushstring(L, "sound");
        lua_gettable(L, 5);
        if (lua_isstring(L, -1)) {
            notification.soundName = [NSString stringWithUTF8String:lua_tostring(L, -1)];
        }
        lua_pop(L, 1);
        */
    }

    uint64_t t = dmTime::GetTime();

    ScheduledNotification sn;

    // Use the current time to create a unique identifier. It should be unique between sessions
    sn.id = (int32_t) (dmHashBuffer64(&t, (uint32_t)sizeof(t)) & 0xFFFFFFFF);
    if (sn.id < 0) {
        sn.id = -sn.id; // JNI doesn't support unsigned int
    }

    sn.timestamp = t + ((uint64_t)seconds) * 1000000L; // in microseconds

    sn.title     = strdup(title);
    sn.message   = strdup(message);
    sn.payload   = strdup(payload);
    sn.priority  = priority;
    if (g_Push.m_ScheduledNotifications.Remaining() == 0) {
        g_Push.m_ScheduledNotifications.SetCapacity(g_Push.m_ScheduledNotifications.Capacity()*2);
    }
    g_Push.m_ScheduledNotifications.Push( sn );

    JNIEnv* env = Attach();
    jstring jtitle   = env->NewStringUTF(sn.title);
    jstring jmessage = env->NewStringUTF(sn.message);
    jstring jpayload = env->NewStringUTF(sn.payload);
    env->CallVoidMethod(g_Push.m_Push, g_Push.m_Schedule, dmGraphics::GetNativeAndroidActivity(), sn.id, sn.timestamp / 1000, jtitle, jmessage, jpayload, sn.priority);
    env->DeleteLocalRef(jpayload);
    env->DeleteLocalRef(jmessage);
    env->DeleteLocalRef(jtitle);
    Detach();

    assert(top == lua_gettop(L));

    lua_pushnumber(L, sn.id);
    return 1;

}

static void RemoveNotification(int id)
{
    for (unsigned int i = 0; i < g_Push.m_ScheduledNotifications.Size(); ++i)
    {
        ScheduledNotification sn = g_Push.m_ScheduledNotifications[i];

        if (sn.id == id)
        {
            if (sn.title) {
                free(sn.title);
            }

            if (sn.message) {
                free(sn.message);
            }

            if (sn.payload) {
                free(sn.payload);
            }

            g_Push.m_ScheduledNotifications.EraseSwap(i);
            break;
        }
    }
}

static int Push_Cancel(lua_State* L)
{
    DM_LUA_STACK_CHECK(L, 0);
    int cancel_id = luaL_checkinteger(L, 1);

    for (unsigned int i = 0; i < g_Push.m_ScheduledNotifications.Size(); ++i)
    {
        ScheduledNotification sn = g_Push.m_ScheduledNotifications[i];

        if (sn.id == cancel_id)
        {
            JNIEnv* env = Attach();
            jstring jtitle   = env->NewStringUTF(sn.title);
            jstring jmessage = env->NewStringUTF(sn.message);
            jstring jpayload = env->NewStringUTF(sn.payload);
            env->CallVoidMethod(g_Push.m_Push, g_Push.m_Cancel, dmGraphics::GetNativeAndroidActivity(), sn.id, jtitle, jmessage, jpayload, sn.priority);
            env->DeleteLocalRef(jpayload);
            env->DeleteLocalRef(jmessage);
            env->DeleteLocalRef(jtitle);
            Detach();

            RemoveNotification(cancel_id);
            break;
        }
    }

    return 0;
}

static void NotificationToLua(lua_State* L, ScheduledNotification notification)
{
    lua_createtable(L, 0, 5);

    lua_pushstring(L, "seconds");
    lua_pushnumber(L, (notification.timestamp - dmTime::GetTime()) / 1000000.0);
    lua_settable(L, -3);

    lua_pushstring(L, "title");
    lua_pushstring(L, notification.title);
    lua_settable(L, -3);

    lua_pushstring(L, "message");
    lua_pushstring(L, notification.message);
    lua_settable(L, -3);

    lua_pushstring(L, "payload");
    lua_pushstring(L, notification.payload);
    lua_settable(L, -3);

    lua_pushstring(L, "priority");
    lua_pushnumber(L, notification.priority);
    lua_settable(L, -3);

}

static int Push_GetScheduled(lua_State* L)
{
    DM_LUA_STACK_CHECK(L, 1);

    int get_id = luaL_checkinteger(L, 1);
    uint64_t cur_time = dmTime::GetTime();

    for (unsigned int i = 0; i < g_Push.m_ScheduledNotifications.Size(); ++i)
    {
        ScheduledNotification sn = g_Push.m_ScheduledNotifications[i];

        // filter out and remove notifications that have elapsed
        if (sn.timestamp <= cur_time)
        {
            RemoveNotification(sn.id);
            i--;
            continue;
        }

        if (sn.id == get_id)
        {
            NotificationToLua(L, sn);
            return 1;
        }
    }
    lua_pushnil(L);
    return 1;
}

static int Push_GetAllScheduled(lua_State* L)
{
    DM_LUA_STACK_CHECK(L, 1);

    uint64_t cur_time = dmTime::GetTime();

    lua_createtable(L, 0, 0);
    for (unsigned int i = 0; i < g_Push.m_ScheduledNotifications.Size(); ++i)
    {
        ScheduledNotification sn = g_Push.m_ScheduledNotifications[i];

        // filter out and remove notifications that have elapsed
        if (sn.timestamp <= cur_time)
        {
            RemoveNotification(sn.id);
            i--;
            continue;
        }

        lua_pushnumber(L, sn.id);
        NotificationToLua(L, sn);
        lua_settable(L, -3);
    }

    return 1;
}

static const luaL_reg Push_methods[] =
{
    {"register", Push_Register},
    {"set_listener", Push_SetListener},

    {"schedule", Push_Schedule},
    {"cancel", Push_Cancel},
    {"get_scheduled", Push_GetScheduled},
    {"get_all_scheduled", Push_GetAllScheduled},

    {0, 0}
};


#ifdef __cplusplus
extern "C" {
#endif

JNIEXPORT void JNICALL Java_com_defold_push_PushJNI_addPendingNotifications(JNIEnv* env, jobject, jint uid, jstring title, jstring message, jstring payload, jlong timestampMillis, jint priority)
{
    uint64_t cur_time = dmTime::GetTime();
    uint64_t timestamp = 1000 * (uint64_t)timestampMillis;
    if (timestamp <= cur_time) {
        return;
    }

    const char* c_title = "";
    const char* c_message = "";
    const char* c_payload = "";
    if (title) {
        c_title = env->GetStringUTFChars(title, 0);
    }
    if (message) {
        c_message = env->GetStringUTFChars(message, 0);
    }
    if (payload) {
        c_payload = env->GetStringUTFChars(payload, 0);
    }

    ScheduledNotification sn;
    sn.id        = (uint64_t)uid;
    sn.timestamp = timestamp;
    sn.title     = strdup(c_title);
    sn.message   = strdup(c_message);
    sn.payload   = strdup(c_payload);
    sn.priority  = (int)priority;

    if (g_Push.m_ScheduledNotifications.Remaining() == 0) {
        g_Push.m_ScheduledNotifications.SetCapacity(g_Push.m_ScheduledNotifications.Capacity()*2);
    }
    g_Push.m_ScheduledNotifications.Push( sn );


    if (c_title) {
        env->ReleaseStringUTFChars(title, c_title);
    }
    if (c_message) {
        env->ReleaseStringUTFChars(message, c_message);
    }
    if (c_payload) {
        env->ReleaseStringUTFChars(payload, c_payload);
    }
}

JNIEXPORT void JNICALL Java_com_defold_push_PushJNI_onRegistration(JNIEnv* env, jobject, jstring regId, jstring errorMessage)
{
    const char* ri = 0;
    const char* em = 0;

    if (regId)
    {
        ri = env->GetStringUTFChars(regId, 0);
    }
    if (errorMessage)
    {
        em = env->GetStringUTFChars(errorMessage, 0);
    }

    dmPush::Command cmd;
    cmd.m_Callback = g_Push.m_Callback;
    cmd.m_Command = dmPush::COMMAND_TYPE_REGISTRATION_RESULT;
    if (ri) {
        cmd.m_Result = strdup(ri);
        env->ReleaseStringUTFChars(regId, ri);
    }
    if (em) {
        cmd.m_Error = strdup(em);
        env->ReleaseStringUTFChars(errorMessage, em);
    }
    dmPush::QueuePush(&g_Push.m_CommandQueue, &cmd);
    g_Push.m_Callback = 0;
}


JNIEXPORT void JNICALL Java_com_defold_push_PushJNI_onMessage(JNIEnv* env, jobject, jstring json, bool wasActivated)
{
    const char* j = 0;

    if (json)
    {
        j = env->GetStringUTFChars(json, 0);
    }

    dmPush::Command cmd;
    cmd.m_Callback = g_Push.m_Listener;
    cmd.m_Command = dmPush::COMMAND_TYPE_PUSH_MESSAGE_RESULT;
    cmd.m_Result = strdup(j);
    cmd.m_WasActivated = wasActivated;
    dmPush::QueuePush(&g_Push.m_CommandQueue, &cmd);
    if (j)
    {
        env->ReleaseStringUTFChars(json, j);
    }
}

JNIEXPORT void JNICALL Java_com_defold_push_PushJNI_onLocalMessage(JNIEnv* env, jobject, jstring json, int id, bool wasActivated)
{
    const char* j = 0;

    if (json)
    {
        j = env->GetStringUTFChars(json, 0);
    }

    // keeping track of local notifications, need to remove from internal list
    RemoveNotification(id);

    dmPush::Command cmd;
    cmd.m_Callback = g_Push.m_Listener;
    cmd.m_Command = dmPush::COMMAND_TYPE_LOCAL_MESSAGE_RESULT;
    cmd.m_Result = strdup(j);
    cmd.m_WasActivated = wasActivated;
    dmPush::QueuePush(&g_Push.m_CommandQueue, &cmd);
    if (j)
    {
        env->ReleaseStringUTFChars(json, j);
    }
}

#ifdef __cplusplus
}
#endif


static dmExtension::Result AppInitializePush(dmExtension::AppParams* params)
{
    dmPush::QueueCreate(&g_Push.m_CommandQueue);

    JNIEnv* env = Attach();

    jclass activity_class = env->FindClass("android/app/NativeActivity");
    jmethodID get_class_loader = env->GetMethodID(activity_class,"getClassLoader", "()Ljava/lang/ClassLoader;");
    jobject cls = env->CallObjectMethod(dmGraphics::GetNativeAndroidActivity(), get_class_loader);
    jclass class_loader = env->FindClass("java/lang/ClassLoader");
    jmethodID find_class = env->GetMethodID(class_loader, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");

    jstring str_class_name = env->NewStringUTF("com.defold.push.Push");
    jclass push_class = (jclass)env->CallObjectMethod(cls, find_class, str_class_name);
    env->DeleteLocalRef(str_class_name);

    str_class_name = env->NewStringUTF("com.defold.push.PushJNI");
    jclass push_jni_class = (jclass)env->CallObjectMethod(cls, find_class, str_class_name);
    env->DeleteLocalRef(str_class_name);

    g_Push.m_Start = env->GetMethodID(push_class, "start", "(Landroid/app/Activity;Lcom/defold/push/IPushListener;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
    g_Push.m_Stop = env->GetMethodID(push_class, "stop", "()V");
    g_Push.m_FlushStored = env->GetMethodID(push_class, "flushStoredNotifications", "()V");
    g_Push.m_Register = env->GetMethodID(push_class, "register", "(Landroid/app/Activity;)V");
    g_Push.m_Schedule = env->GetMethodID(push_class, "scheduleNotification", "(Landroid/app/Activity;IJLjava/lang/String;Ljava/lang/String;Ljava/lang/String;I)V");
    g_Push.m_Cancel = env->GetMethodID(push_class, "cancelNotification", "(Landroid/app/Activity;ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;I)V");

    jmethodID get_instance_method = env->GetStaticMethodID(push_class, "getInstance", "()Lcom/defold/push/Push;");
    g_Push.m_Push = env->NewGlobalRef(env->CallStaticObjectMethod(push_class, get_instance_method));

    jmethodID setListener = env->GetMethodID(push_class, "setApplicationListener", "(Landroid/app/Activity;)V");
    env->CallVoidMethod(g_Push.m_Push, setListener, dmGraphics::GetNativeAndroidActivity());

    jmethodID jni_constructor = env->GetMethodID(push_jni_class, "<init>", "()V");
    g_Push.m_PushJNI = env->NewGlobalRef(env->NewObject(push_jni_class, jni_constructor));

    const char* sender_id = dmConfigFile::GetString(params->m_ConfigFile, "android.gcm_sender_id", "");
    const char* application_id = dmConfigFile::GetString(params->m_ConfigFile, "android.fcm_application_id", "");
    const char* project_title = dmConfigFile::GetString(params->m_ConfigFile, "project.title", "");
    jstring sender_id_string = env->NewStringUTF(sender_id);
    jstring application_id_string = env->NewStringUTF(application_id);
    jstring project_title_string = env->NewStringUTF(project_title);
    env->CallVoidMethod(g_Push.m_Push, g_Push.m_Start, dmGraphics::GetNativeAndroidActivity(), g_Push.m_PushJNI, sender_id_string, application_id_string, project_title_string);
    env->DeleteLocalRef(sender_id_string);
    env->DeleteLocalRef(application_id_string);
    env->DeleteLocalRef(project_title_string);

    // loop through all stored local push notifications
    jmethodID loadPendingNotifications = env->GetMethodID(push_class, "loadPendingNotifications", "(Landroid/app/Activity;)V");
    env->CallVoidMethod(g_Push.m_Push, loadPendingNotifications, dmGraphics::GetNativeAndroidActivity());

    Detach();

    return dmExtension::RESULT_OK;
}

static dmExtension::Result UpdatePush(dmExtension::Params* params)
{
    dmPush::QueueFlush(&g_Push.m_CommandQueue, dmPush::HandleCommand, 0);
    return dmExtension::RESULT_OK;
}

static dmExtension::Result AppFinalizePush(dmExtension::AppParams* params)
{
    JNIEnv* env = Attach();
    env->CallVoidMethod(g_Push.m_Push, g_Push.m_Stop);
    env->DeleteGlobalRef(g_Push.m_Push);
    env->DeleteGlobalRef(g_Push.m_PushJNI);
    Detach();
    g_Push.m_Push = NULL;
    g_Push.m_PushJNI = NULL;

    dmPush::QueueDestroy(&g_Push.m_CommandQueue);

    return dmExtension::RESULT_OK;
}

static dmExtension::Result InitializePush(dmExtension::Params* params)
{
    lua_State* L = params->m_L;
    int top = lua_gettop(L);
    luaL_register(L, LIB_NAME, Push_methods);

#define SETCONSTANT(name, val) \
        lua_pushnumber(L, (lua_Number) val); \
        lua_setfield(L, -2, #name);\

    // Values from http://developer.android.com/reference/android/support/v4/app/NotificationCompat.html#PRIORITY_DEFAULT
    SETCONSTANT(PRIORITY_MIN,     -2);
    SETCONSTANT(PRIORITY_LOW,     -1);
    SETCONSTANT(PRIORITY_DEFAULT,  0);
    SETCONSTANT(PRIORITY_HIGH,     1);
    SETCONSTANT(PRIORITY_MAX,      2);

    SETCONSTANT(ORIGIN_REMOTE, dmPush::ORIGIN_REMOTE);
    SETCONSTANT(ORIGIN_LOCAL,  dmPush::ORIGIN_LOCAL);

#undef SETCONSTANT

    lua_pop(L, 1);
    assert(top == lua_gettop(L));
    return dmExtension::RESULT_OK;
}

static dmExtension::Result FinalizePush(dmExtension::Params* params)
{
    if (g_Push.m_Listener)
        dmScript::DestroyCallback(g_Push.m_Listener);
    return dmExtension::RESULT_OK;
}

DM_DECLARE_EXTENSION(PushExtExternal, "Push", AppInitializePush, AppFinalizePush, InitializePush, UpdatePush, 0, FinalizePush)
#endif // DM_PLATFORM_ANDROID
