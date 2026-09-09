#include "storage_error.h"

StorageError::StorageError(std::string code, const std::string &message)
    : std::runtime_error(message), code_(std::move(code))
{
}

const std::string &StorageError::code() const
{
    return code_;
}
