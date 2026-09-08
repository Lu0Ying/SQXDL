#include <iostream>
#include "nlohmann/json.hpp"

int main(int argc, char *argv[])
{
    if (argc != 2)
    {
        std::cerr << "Error: Expected exactly one argument (physic plan JSON)" << std::endl;
        return 1;
    }

    std::string physic_plan_json = argv[1];
    nlohmann::json physic_plan = nlohmann::json::parse(physic_plan_json);
    std::cout << "JSON OK" << std::endl;
    std::cout << physic_plan << std::endl;
    return 0;
}