#if defined(DM_PLATFORM_ANDROID) || defined(DM_PLATFORM_IOS)
#include <dmsdk/sdk.h>
#include <stdlib.h> // free

#include "push_utils.h"

bool dmPush::VerifyPayload(lua_State* L, const char* payload, char* error_str_out, size_t error_str_size)
{
    int top = lua_gettop(L);
    bool success = false;

    dmJson::Document doc;
    dmJson::Result r = dmJson::Parse(payload, &doc);
    if (r == dmJson::RESULT_OK && doc.m_NodeCount > 0) {
        if (dmScript::JsonToLua(L, &doc, 0, error_str_out, error_str_size) >= 0) {
            success = true;
            // JsonToLua will push Lua values on the stack, but they will not be used
            // since we only want to verify that the JSON can be converted to Lua here.
            lua_pop(L, lua_gettop(L) - top);
        }
    } else {
        dmLogError("Failed to parse JSON payload string (%d)", r);
    }
    dmJson::Free(&doc);

    assert(top == lua_gettop(L));
    return success;
}

static void PushError(lua_State* L, const char* error)
{
    // Could be extended with error codes etc
    if (error != 0) {
        lua_newtable(L);
        lua_pushstring(L, "error");
        lua_pushstring(L, error);
        lua_rawset(L, -3);
    } else {
        lua_pushnil(L);
    }
}

static void HandleRegistrationResult(const dmPush::Command* cmd)
{
    if (!dmScript::IsCallbackValid(cmd->m_Callback))
    {
        return;
    }

    lua_State* L = dmScript::GetCallbackLuaContext(cmd->m_Callback);
    DM_LUA_STACK_CHECK(L, 0);

    if (!dmScript::SetupCallback(cmd->m_Callback))
    {
        return;
    }

    if (cmd->m_Result) {
        lua_pushstring(L, cmd->m_Result);
        lua_pushnil(L);
    } else {
        lua_pushnil(L);
        PushError(L, cmd->m_Error);
        dmLogError("HandleRegistrationResult: %s", cmd->m_Error);
    }

    int ret = dmScript::PCall(L, 3, 0);
    (void)ret;

    dmScript::TeardownCallback(cmd->m_Callback);
}


static void HandlePushMessageResult(const dmPush::Command* cmd, bool local)
{
    if (!dmScript::IsCallbackValid(cmd->m_Callback))
    {
        return;
    }

    lua_State* L = dmScript::GetCallbackLuaContext(cmd->m_Callback);
    DM_LUA_STACK_CHECK(L, 0);

    if (!dmScript::SetupCallback(cmd->m_Callback))
    {
        return;
    }

    dmJson::Document doc;
    dmJson::Result r = dmJson::Parse((const char*) cmd->m_Result, &doc);
    if (r == dmJson::RESULT_OK && doc.m_NodeCount > 0) {
        char err_str[128];
        if (dmScript::JsonToLua(L, &doc, 0, err_str, sizeof(err_str)) < 0) {
            dmLogError("Failed converting push result JSON to Lua; %s", err_str);
            dmJson::Free(&doc);
            return;
        }

        lua_pushnumber(L, local ? dmPush::ORIGIN_LOCAL : dmPush::ORIGIN_REMOTE);
        lua_pushboolean(L, cmd->m_WasActivated);

        int ret = dmScript::PCall(L, 4, 0);
        (void)ret;
    } else {
        lua_pop(L, 2);
        dmLogError("Failed to parse push response (%d)", r);
    }
    dmJson::Free(&doc);

    dmScript::TeardownCallback(cmd->m_Callback);
}

void dmPush::HandleCommand(dmPush::Command* cmd, void* ctx)
{
    switch (cmd->m_Command)
    {
    case dmPush::COMMAND_TYPE_REGISTRATION_RESULT:  HandleRegistrationResult(cmd); break;
    case dmPush::COMMAND_TYPE_PUSH_MESSAGE_RESULT:  HandlePushMessageResult(cmd, false); break;
    case dmPush::COMMAND_TYPE_LOCAL_MESSAGE_RESULT: HandlePushMessageResult(cmd, true); break;
    default: assert(false);
    }
    free((void*)cmd->m_Result);
    free((void*)cmd->m_Error);

    if (cmd->m_Command == dmPush::COMMAND_TYPE_REGISTRATION_RESULT)
        dmScript::DestroyCallback(cmd->m_Callback);
}

void dmPush::QueueCreate(CommandQueue* queue)
{
    queue->m_Mutex = dmMutex::New();
}

void dmPush::QueueDestroy(CommandQueue* queue)
{
    {
        DM_MUTEX_SCOPED_LOCK(queue->m_Mutex);
        queue->m_Commands.SetSize(0);
    }
    dmMutex::Delete(queue->m_Mutex);
}

void dmPush::QueuePush(CommandQueue* queue, Command* cmd)
{
    DM_MUTEX_SCOPED_LOCK(queue->m_Mutex);

    if(queue->m_Commands.Full())
    {
        queue->m_Commands.OffsetCapacity(2);
    }
    queue->m_Commands.Push(*cmd);
}

void dmPush::QueueFlush(CommandQueue* queue, CommandFn fn, void* ctx)
{
    assert(fn != 0);
    if (queue->m_Commands.Empty())
    {
        return;
    }

    DM_MUTEX_SCOPED_LOCK(queue->m_Mutex);

    for(uint32_t i = 0; i != queue->m_Commands.Size(); ++i)
    {
        fn(&queue->m_Commands[i], ctx);
    }
    queue->m_Commands.SetSize(0);
}


#endif // DM_PLATFORM_ANDROID || DM_PLATFORM_IOS
