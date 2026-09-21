#if defined(DM_PLATFORM_IOS)
#include "push_utils.h"

#include <dmsdk/sdk.h>

#import <Foundation/Foundation.h>
#import <UIKit/UIKit.h>
#import <UserNotifications/UserNotifications.h>

#define LIB_NAME "push"

struct Push
{
    Push()
    {
        memset(this, 0, sizeof(*this));
        m_ScheduledID = -1;
    }

    bool IsInitialized() { return m_Initialized; }

    void Initialize() {
        dmPush::QueueCreate(&m_CommandQueue);
        dmPush::QueueCreate(&m_SavedNotifications);
        m_Initialized = true;
    }

    bool                        m_Initialized;
    dmScript::LuaCallbackInfo*  m_Callback;
    dmScript::LuaCallbackInfo*  m_Listener;
    dmPush::CommandQueue        m_CommandQueue;
    dmPush::CommandQueue        m_SavedNotifications;
    int                         m_ScheduledID;
};

static Push g_Push;

static void PushError(lua_State*L, NSError* error)
{
    // Could be extended with error codes etc
    if (error != 0) {
        lua_newtable(L);
        lua_pushstring(L, "error");
        lua_pushstring(L, [error.localizedDescription UTF8String]);
        lua_rawset(L, -3);
    } else {
        lua_pushnil(L);
    }
}

static void UpdateScheduleIDCounter()
{
    for (id obj in [[UIApplication sharedApplication] scheduledLocalNotifications]) {
        UILocalNotification* notification = (UILocalNotification*)obj;
        int current_id = [(NSNumber*)notification.userInfo[@"id"] intValue];
        if (current_id > g_Push.m_ScheduledID)
        {
            g_Push.m_ScheduledID = current_id;
        }
    }

}

static const char* ObjCToJson(id obj)
{
    NSError* error;
    NSData* jsonData = [NSJSONSerialization dataWithJSONObject:obj options:(NSJSONWritingOptions)0 error:&error];
    if (!jsonData)
    {
        return 0;
    }
    NSString* nsstring = [[NSString alloc] initWithData:jsonData encoding:NSUTF8StringEncoding];
    const char* json = strdup([nsstring UTF8String]);
    [nsstring release];
    return json;
}

static void QueueNotification(NSDictionary* userInfo, bool local, bool wasActivated)
{
    dmPush::Command cmd;
    cmd.m_Command = local ? dmPush::COMMAND_TYPE_LOCAL_MESSAGE_RESULT : dmPush::COMMAND_TYPE_PUSH_MESSAGE_RESULT;
    if (local) {
        NSString* payload = userInfo[@"payload"];
        cmd.m_Result = strdup(payload ? [payload UTF8String] : "{}");
    } else {
        cmd.m_Result = ObjCToJson(userInfo);
    }
    cmd.m_WasActivated = wasActivated;
    // Bind the listener at delivery time, so replacing it or rebooting cannot
    // leave a queued notification pointing to a destroyed Lua callback.
    dmPush::QueuePush(&g_Push.m_SavedNotifications, &cmd);
}

static void DeliverNotification(dmPush::Command* cmd, void* ctx)
{
    if (g_Push.m_Listener) {
        cmd->m_Callback = g_Push.m_Listener;
        dmPush::HandleCommand(cmd, ctx);
    } else {
        dmPush::QueuePush(&g_Push.m_SavedNotifications, cmd);
    }
}

@interface PushAppDelegate : NSObject <UIApplicationDelegate, UISceneDelegate, UNUserNotificationCenterDelegate>
{
    UNNotificationResponse* m_LastResponse;
}
- (void)handleNotificationResponse:(UNNotificationResponse*)response;
@end

@implementation PushAppDelegate

- (BOOL)application:(UIApplication*)application didFinishLaunchingWithOptions:(NSDictionary*)options {
    [UNUserNotificationCenter currentNotificationCenter].delegate = self;
    return YES;
}

- (void)application:(UIApplication*)application didReceiveRemoteNotification:(NSDictionary*)userInfo fetchCompletionHandler:(void (^)(UIBackgroundFetchResult))completionHandler {
    QueueNotification(userInfo, false, application.applicationState != UIApplicationStateActive);
    completionHandler(UIBackgroundFetchResultNoData);
}

- (void)userNotificationCenter:(UNUserNotificationCenter*)center willPresentNotification:(UNNotification*)notification withCompletionHandler:(void (^)(UNNotificationPresentationOptions))completionHandler {
    dispatch_async(dispatch_get_main_queue(), ^{
        bool local = ![notification.request.trigger isKindOfClass:[UNPushNotificationTrigger class]];
        QueueNotification(notification.request.content.userInfo, local, false);
        // Preserve foreground delivery to Lua without showing a system banner.
        completionHandler(UNNotificationPresentationOptionNone);
    });
}

- (void)userNotificationCenter:(UNUserNotificationCenter*)center didReceiveNotificationResponse:(UNNotificationResponse*)response withCompletionHandler:(void (^)(void))completionHandler {
    dispatch_async(dispatch_get_main_queue(), ^{
        [self handleNotificationResponse:response];
        completionHandler();
    });
}

- (void)handleNotificationResponse:(UNNotificationResponse*)response {
    if (!response || [response.actionIdentifier isEqualToString:UNNotificationDismissActionIdentifier])
        return;

    // UIKit can expose the same cold-launch response through both the scene and
    // notification center. Compare delivery identity, not the notification payload.
    if ([m_LastResponse.notification.request.identifier isEqualToString:response.notification.request.identifier]
        && [m_LastResponse.notification.date isEqualToDate:response.notification.date]
        && [m_LastResponse.actionIdentifier isEqualToString:response.actionIdentifier])
        return;
    [m_LastResponse release];
    m_LastResponse = [response retain];

    UNNotificationRequest* request = response.notification.request;
    bool local = ![request.trigger isKindOfClass:[UNPushNotificationTrigger class]];
    QueueNotification(request.content.userInfo, local, true);
}

- (void)scene:(UIScene*)scene willConnectToSession:(UISceneSession*)session options:(UISceneConnectionOptions*)options {
    // Initial connection precedes Lua listener registration.
    [self handleNotificationResponse:options.notificationResponse];
}

- (void)dealloc {
    [m_LastResponse release];
    [super dealloc];
}

- (void)application:(UIApplication *)application didRegisterForRemoteNotificationsWithDeviceToken:(NSData *)deviceToken {
    if (g_Push.m_Callback) {
        const char* result = 0;
        if (deviceToken) {
            const uint32_t* tokenBytes = (const uint32_t*)[deviceToken bytes];
            NSString* hexToken = [NSString stringWithFormat:@"%08x%08x%08x%08x%08x%08x%08x%08x",
                                 ntohl(tokenBytes[0]), ntohl(tokenBytes[1]), ntohl(tokenBytes[2]),
                                 ntohl(tokenBytes[3]), ntohl(tokenBytes[4]), ntohl(tokenBytes[5]),
                                 ntohl(tokenBytes[6]), ntohl(tokenBytes[7])];
            result = hexToken ? strdup([hexToken UTF8String]) : 0;
        }

        dmPush::Command cmd;
        cmd.m_Callback = g_Push.m_Callback;
        cmd.m_Command = dmPush::COMMAND_TYPE_REGISTRATION_RESULT;
        cmd.m_Result = result;
        dmPush::QueuePush(&g_Push.m_CommandQueue, &cmd);
        g_Push.m_Callback = 0;
    }
}

- (void)application:(UIApplication *)application didFailToRegisterForRemoteNotificationsWithError:(NSError *)error {
    dmLogWarning("Failed to register remote notifications: %s\n", [error.localizedDescription UTF8String]);
    if (g_Push.m_Callback) {
        dmPush::Command cmd;
        cmd.m_Callback = g_Push.m_Callback;
        cmd.m_Command = dmPush::COMMAND_TYPE_REGISTRATION_RESULT;
        cmd.m_Error = error ? strdup([error.localizedDescription UTF8String]) : 0;
        dmPush::QueuePush(&g_Push.m_CommandQueue, &cmd);
        g_Push.m_Callback = 0;
    }
}

@end

static int Push_Register(lua_State* L)
{
    int top = lua_gettop(L);

    if (!lua_istable(L, 1)) {
        assert(top == lua_gettop(L));
        luaL_error(L, "First argument must be a table of notification types.");
        return 0;
    }

    // Deprecated in iOS 8
    UIRemoteNotificationType types = UIRemoteNotificationTypeNone;
    lua_pushnil(L);
    while (lua_next(L, 1) != 0) {
        int t = luaL_checkinteger(L, -1);
        types |= (UIRemoteNotificationType) t;
        lua_pop(L, 1);
    }

    if (g_Push.m_Callback)
        dmScript::DestroyCallback(g_Push.m_Callback);

    g_Push.m_Callback = dmScript::CreateCallback(L, 2);

    // iOS 8 API
    if ([[UIApplication sharedApplication] respondsToSelector:@selector(registerUserNotificationSettings:)]) {
        UIUserNotificationType uitypes = UIUserNotificationTypeNone;

        if (types & UIRemoteNotificationTypeBadge)
            uitypes |= UIUserNotificationTypeBadge;

        if (types & UIRemoteNotificationTypeSound)
            uitypes |= UIUserNotificationTypeSound;

        if (types & UIRemoteNotificationTypeAlert)
            uitypes |= UIUserNotificationTypeAlert;

        [[UIApplication sharedApplication] registerUserNotificationSettings:[UIUserNotificationSettings settingsForTypes:uitypes categories:nil]];
        [[UIApplication sharedApplication] registerForRemoteNotifications];

    } else {
        [[UIApplication sharedApplication] registerForRemoteNotificationTypes: types];
    }


    assert(top == lua_gettop(L));
    return 0;
}

static int Push_SetListener(lua_State* L)
{
    DM_LUA_STACK_CHECK(L, 0);

    if (g_Push.m_Listener)
        dmScript::DestroyCallback(g_Push.m_Listener);

    g_Push.m_Listener = dmScript::CreateCallback(L, 1);
    return 0;
}

static int Push_SetBadgeCount(lua_State* L)
{
    DM_LUA_STACK_CHECK(L, 0);
    int count = luaL_checkinteger(L, 1);
    [UIApplication sharedApplication].applicationIconBadgeNumber = count;
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

    NSString* title = [NSString stringWithUTF8String:luaL_checkstring(L, 2)];
    NSString* message = [NSString stringWithUTF8String:luaL_checkstring(L, 3)];

    if (g_Push.m_ScheduledID == -1)
    {
        g_Push.m_ScheduledID = 0;
        UpdateScheduleIDCounter();
    }

    // param: userdata
    NSMutableDictionary* userdata = [NSMutableDictionary dictionaryWithCapacity:2];
    userdata[@"id"] = [NSNumber numberWithInt:g_Push.m_ScheduledID];
    if (top > 3) {
        const char* payload = luaL_checkstring(L, 4);
        
        // Verify that the payload is valid and can be delivered later on.
        dmPush::VerifyPayload(L, payload);

        userdata[@"payload"] = [NSString stringWithUTF8String:payload];
    } else {
        userdata[@"payload"] = nil;
    }

    UILocalNotification* notification = [[UILocalNotification alloc] init];
    if (notification == nil)
    {
        lua_pushnil(L);
        lua_pushstring(L, "could not allocate local notification");
        return 2;
    }

    notification.fireDate   = [NSDate dateWithTimeIntervalSinceNow:seconds];
    notification.timeZone   = [NSTimeZone defaultTimeZone];
    if ([notification respondsToSelector:@selector(alertTitle)]) {
        [notification setValue:title forKey:@"alertTitle"];
    }
    notification.alertBody  = message;
    notification.soundName  = UILocalNotificationDefaultSoundName;
    notification.userInfo   = userdata;

    // param: notification_settings
    if (top > 4) {
        luaL_checktype(L, 5, LUA_TTABLE);

        // action
        lua_pushstring(L, "action");
        lua_gettable(L, 5);
        if (lua_isstring(L, -1)) {
            notification.alertAction = [NSString stringWithUTF8String:lua_tostring(L, -1)];
        }
        lua_pop(L, 1);

        // badge_count
        lua_pushstring(L, "badge_count");
        lua_gettable(L, 5);
        bool badge_count_set = false;
        if (lua_isnumber(L, -1)) {
            notification.applicationIconBadgeNumber = lua_tointeger(L, -1);
            badge_count_set = true;
        }
        lua_pop(L, 1);

        // Deprecated, replaced by badge_count
        if(!badge_count_set)
        {
            lua_pushstring(L, "badge_number");
            lua_gettable(L, 5);
            if (lua_isnumber(L, -1)) {
                notification.applicationIconBadgeNumber = lua_tointeger(L, -1);
            }
            lua_pop(L, 1);
        }

        // sound
        /*

        There is no way of automatically bundle files inside the .app folder (i.e. skipping
        archiving them inside the .darc), but to have custom notification sounds they need to
        be accessable from the .app folder.

        lua_pushstring(L, "sound");
        lua_gettable(L, 5);
        if (lua_isstring(L, -1)) {
            notification.soundName = [NSString stringWithUTF8String:lua_tostring(L, -1)];
        }
        lua_pop(L, 1);
        */
    }

    [[UIApplication sharedApplication] scheduleLocalNotification:notification];

    assert(top == lua_gettop(L));

    // need to remember notification so it can be canceled later on
    lua_pushnumber(L, g_Push.m_ScheduledID++);

    return 1;
}

static int Push_Cancel(lua_State* L)
{
    DM_LUA_STACK_CHECK(L, 0);

    int cancel_id = luaL_checkinteger(L, 1);
    for (id obj in [[UIApplication sharedApplication] scheduledLocalNotifications]) {
        UILocalNotification* notification = (UILocalNotification*)obj;

        if ([(NSNumber*)notification.userInfo[@"id"] intValue] == cancel_id)
        {
            [[UIApplication sharedApplication] cancelLocalNotification:notification];
            return 0;
        }
    }

    return 0;
}

static void NotificationToLua(lua_State* L, UILocalNotification* notification)
{
    lua_createtable(L, 0, 6);

    lua_pushstring(L, "seconds");
    lua_pushnumber(L, [[notification fireDate] timeIntervalSinceNow]);
    lua_settable(L, -3);

    lua_pushstring(L, "title");
    if ([notification respondsToSelector:@selector(alertTitle)]) {
        lua_pushstring(L, [[notification valueForKey:@"alertTitle"] UTF8String]);
    } else {
        lua_pushnil(L);
    }
    lua_settable(L, -3);

    lua_pushstring(L, "message");
    lua_pushstring(L, [[notification alertBody] UTF8String]);
    lua_settable(L, -3);

    lua_pushstring(L, "payload");
    NSString* payload = (NSString*)[[notification userInfo] objectForKey:@"payload"];
    lua_pushstring(L, [payload UTF8String]);
    lua_settable(L, -3);

    lua_pushstring(L, "action");
    lua_pushstring(L, [[notification alertAction] UTF8String]);
    lua_settable(L, -3);

    lua_pushstring(L, "badge_count");
    lua_pushnumber(L, [notification applicationIconBadgeNumber]);
    lua_settable(L, -3);

    // Deprecated
    lua_pushstring(L, "badge_number");
    lua_pushnumber(L, [notification applicationIconBadgeNumber]);
    lua_settable(L, -3);
}

static int Push_GetScheduled(lua_State* L)
{
    DM_LUA_STACK_CHECK(L, 1);

    int get_id = luaL_checkinteger(L, 1);
    for (id obj in [[UIApplication sharedApplication] scheduledLocalNotifications]) {
        UILocalNotification* notification = (UILocalNotification*)obj;

        if ([(NSNumber*)notification.userInfo[@"id"] intValue] == get_id)
        {
            NotificationToLua(L, notification);
            return 1;
        }
    }
    lua_pushnil(L);
    return 1;
}

static int Push_GetAllScheduled(lua_State* L)
{
    DM_LUA_STACK_CHECK(L, 1);

    lua_createtable(L, 0, 0);
    for (id obj in [[UIApplication sharedApplication] scheduledLocalNotifications]) {

        UILocalNotification* notification = (UILocalNotification*)obj;

        NSNumber* notification_id = (NSNumber*)[[notification userInfo] objectForKey:@"id"];
        lua_pushnumber(L, [notification_id intValue]);
        NotificationToLua(L, notification);
        lua_settable(L, -3);
    }

    return 1;
}

static int Push_CancelAllIssued(lua_State* L)
{
    DM_LUA_STACK_CHECK(L, 0);

    [[UIApplication sharedApplication] setApplicationIconBadgeNumber: 1];
    [[UIApplication sharedApplication] setApplicationIconBadgeNumber: 0];

    return 0;
}

static const luaL_reg Push_methods[] =
{
    {"register", Push_Register},
    {"set_listener", Push_SetListener},
    {"set_badge_count", Push_SetBadgeCount},

    // local
    {"schedule", Push_Schedule},
    {"cancel", Push_Cancel},
    {"get_scheduled", Push_GetScheduled},
    {"get_all_scheduled", Push_GetAllScheduled},
    {"cancel_all_issued", Push_CancelAllIssued},

    {0, 0}
};

static void DiscardCommand(dmPush::Command* cmd, void* ctx)
{
    free((void*)cmd->m_Result);
    free((void*)cmd->m_Error);
    if (cmd->m_Command == dmPush::COMMAND_TYPE_REGISTRATION_RESULT && cmd->m_Callback)
        dmScript::DestroyCallback(cmd->m_Callback);
}

struct PushAppDelegateRegister
{
    PushAppDelegate* m_Delegate;
    PushAppDelegateRegister() {
        // Scene connection can happen before extension app-initialize.
        g_Push.Initialize();
        m_Delegate = [[PushAppDelegate alloc] init];
        dmExtension::RegisteriOSUIApplicationDelegate(m_Delegate);
        dmExtension::RegisteriOSUISceneDelegate(m_Delegate);
    }

    ~PushAppDelegateRegister() {
        dmExtension::UnregisteriOSUISceneDelegate(m_Delegate);
        dmExtension::UnregisteriOSUIApplicationDelegate(m_Delegate);
        if ([UNUserNotificationCenter currentNotificationCenter].delegate == m_Delegate)
            [UNUserNotificationCenter currentNotificationCenter].delegate = nil;
        [m_Delegate release];
        dmPush::QueueFlush(&g_Push.m_CommandQueue, DiscardCommand, 0);
        dmPush::QueueFlush(&g_Push.m_SavedNotifications, DiscardCommand, 0);
        dmPush::QueueDestroy(&g_Push.m_CommandQueue);
        dmPush::QueueDestroy(&g_Push.m_SavedNotifications);
    }
};

static PushAppDelegateRegister g_PushDelegateRegister;

static dmExtension::Result AppInitializePush(dmExtension::AppParams* params)
{
    if (!g_Push.IsInitialized()) {
        g_Push.Initialize();
    }
    return dmExtension::RESULT_OK;
}

static dmExtension::Result UpdatePush(dmExtension::Params* params)
{
    if (g_Push.m_Listener)
        dmPush::QueueFlush(&g_Push.m_SavedNotifications, DeliverNotification, 0);

    dmPush::QueueFlush(&g_Push.m_CommandQueue, dmPush::HandleCommand, 0);
    return dmExtension::RESULT_OK;
}

static dmExtension::Result AppFinalizePush(dmExtension::AppParams* params)
{
    // Queues belong to the registered native observer and survive sys.reboot().
    return dmExtension::RESULT_OK;
}

static dmExtension::Result InitializePush(dmExtension::Params* params)
{
    lua_State*L = params->m_L;
    int top = lua_gettop(L);
    luaL_register(L, LIB_NAME, Push_methods);

#define SETCONSTANT(name, val) \
        lua_pushnumber(L, (lua_Number) val); \
        lua_setfield(L, -2, #name);\

    SETCONSTANT(NOTIFICATION_BADGE, UIRemoteNotificationTypeBadge);
    SETCONSTANT(NOTIFICATION_SOUND, UIRemoteNotificationTypeSound);
    SETCONSTANT(NOTIFICATION_ALERT, UIRemoteNotificationTypeAlert);

    SETCONSTANT(ORIGIN_REMOTE, dmPush::ORIGIN_REMOTE);
    SETCONSTANT(ORIGIN_LOCAL,  dmPush::ORIGIN_LOCAL);

#undef SETCONSTANT

    lua_pop(L, 1);
    assert(top == lua_gettop(L));

    return dmExtension::RESULT_OK;
}

static dmExtension::Result FinalizePush(dmExtension::Params* params)
{
    // Pending notifications must target the next listener after an engine reboot.
    dmPush::QueueFlush(&g_Push.m_CommandQueue, DiscardCommand, 0);
    if (g_Push.m_Callback)
        dmScript::DestroyCallback(g_Push.m_Callback);
    if (g_Push.m_Listener)
        dmScript::DestroyCallback(g_Push.m_Listener);
    g_Push.m_Listener = 0;
    g_Push.m_Callback = 0;
    return dmExtension::RESULT_OK;
}

DM_DECLARE_EXTENSION(PushExtExternal, "Push", AppInitializePush, AppFinalizePush, InitializePush, UpdatePush, 0, FinalizePush)
#endif // DM_PLATFORM_IOS
