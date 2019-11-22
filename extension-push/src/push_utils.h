#if defined(DM_PLATFORM_ANDROID) || defined(DM_PLATFORM_IOS)
#ifndef DM_PUSH_UTILS
#define DM_PUSH_UTILS

#include <dmsdk/sdk.h>

namespace dmPush
{
	enum OriginType
	{
		ORIGIN_REMOTE = 0,
		ORIGIN_LOCAL = 1,
	};

	enum CommandType
	{
		COMMAND_TYPE_REGISTRATION_RESULT  = 0,
		COMMAND_TYPE_PUSH_MESSAGE_RESULT  = 1,
		COMMAND_TYPE_LOCAL_MESSAGE_RESULT = 2,
	};

	struct Command
	{
	    Command()
	    {
	        memset(this, 0, sizeof(Command));
	    }
	    dmScript::LuaCallbackInfo* m_Callback;

	    uint32_t 	m_Command;
	    int32_t  	m_ResponseCode;
	    const char* m_Result;
	    const char* m_Error;
	    bool     	m_WasActivated;
	};

	struct CommandQueue
	{
	    dmArray<Command> m_Commands;
	    dmMutex::HMutex      m_Mutex;
	};

	typedef void (*CommandFn)(Command* cmd, void* ctx);

	void QueueCreate(CommandQueue* queue);
	void QueueDestroy(CommandQueue* queue);
	void QueuePush(CommandQueue* queue, Command* cmd);
	void QueueFlush(CommandQueue* queue, CommandFn fn, void* ctx);

	void HandleCommand(dmPush::Command* push, void* ctx);

    bool VerifyPayload(lua_State* L, const char* payload, char* error_str_out, size_t error_str_size);
}

#endif
#endif // DM_PLATFORM_ANDROID || DM_PLATFORM_IOS
