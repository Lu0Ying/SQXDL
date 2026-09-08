#include <iostream>
#include "nlohmann/json.hpp"

int main()
{
    std::string physic_plan_string((std::istreambuf_iterator<char>(std::cin)),
                                   std::istreambuf_iterator<char>());

    if (physic_plan_string.empty())
    {
        std::cerr << "Error: Expected physic plan JSON on stdin" << std::endl;
        return 1;
    }

    nlohmann::json physic_plan = nlohmann::json::parse(physic_plan_string);

    // std::cout << physic_plan["operation"].get<std::string>() << std::endl;
    return 0;
}